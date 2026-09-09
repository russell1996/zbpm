package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpIllegalStateException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-20: the SSE bridge must never block the HTTP thread on broker RPCs.
 * A stalled broker once hung {@code GET /events/stream} with zero response
 * (no status line for 15s+): {@code registerClient} ran declares +
 * {@code container.start()} (which waits up to the 60s consumer-start
 * timeout) synchronously. Now the start runs on a background thread; the
 * stream opens immediately and events flow once ready.
 *
 * <p>RabbitAdmin is mocked here as the BROKER boundary (like MockMvc is a
 * harness, not prod logic): the assertions are about OUR lifecycle behavior
 * (return latency, attempt counting, rollback cleanup), while the real
 * bridge path is proven live against real RabbitMQ in the WO report.
 */
@ExtendWith(MockitoExtension.class)
class SseBridgeStartupTest {

    @Mock RabbitAdmin rabbitAdmin;
    @Mock EventAuthzResolver eventAuthzResolver;

    private SseEventStreamService service() {
        when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        return new SseEventStreamService(null, eventAuthzResolver, rabbitAdmin, new ObjectMapper());
    }

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    @Test
    void registerClient_returnsFast_whenBrokerBlocksDeclares() throws Exception {
        CountDownLatch enteredDeclare = new CountDownLatch(1);
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenAnswer(inv -> {
            enteredDeclare.countDown();
            Thread.sleep(8000);
            return ((Queue) inv.getArgument(0)).getName();
        });

        SseEventStreamService service = service();
        long start = System.nanoTime();
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
            .as("registerClient must not wait for the blocking broker declare")
            .isLessThan(3000);
        // The background starter really did reach the blocking call.
        assertThat(enteredDeclare.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentRegistrations_startBridgeOnlyOnce() throws Exception {
        CountDownLatch enteredDeclare = new CountDownLatch(1);
        CountDownLatch releaseDeclare = new CountDownLatch(1);
        CountDownLatch attemptFailed = new CountDownLatch(1);
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenAnswer(inv -> {
            enteredDeclare.countDown();
            assertThat(releaseDeclare.await(10, TimeUnit.SECONDS)).isTrue();
            return ((Queue) inv.getArgument(0)).getName();
        });
        // Fail the attempt right after the declare so no real container starts.
        // Counted: guarantees the stub is consumed before the test ends
        // (Mockito strict stubs).
        doAnswer(inv -> {
            attemptFailed.countDown();
            throw new AmqpIllegalStateException("boom");
        }).when(rabbitAdmin).declareBinding(any());

        SseEventStreamService service = service();
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        assertThat(enteredDeclare.await(5, TimeUnit.SECONDS)).isTrue();
        // Second registration while the first start is still blocked inside
        // declareQueue must NOT spawn a duplicate bridge start.
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        Thread.sleep(300);
        verify(rabbitAdmin, timeout(1000).times(1)).declareQueue(any(Queue.class));
        releaseDeclare.countDown();
        assertThat(attemptFailed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void failedStart_retriesOnNextRegistration() throws Exception {
        // Deterministic sequencing: attempt #1 must fully FAIL before
        // register #2 (the flag blocks concurrent duplicates by design).
        // The binding stub signals arrival; the 50ms after covers
        // throw → catch → deleteQueue → finally(flag reset), nanoseconds
        // of real work.
        CountDownLatch attemptReachedBinding = new CountDownLatch(1);
        doAnswer(inv -> ((Queue) inv.getArgument(0)).getName()).when(rabbitAdmin).declareQueue(any(Queue.class));
        doAnswer(inv -> {
            attemptReachedBinding.countDown();
            throw new AmqpIllegalStateException("boom");
        }).when(rabbitAdmin).declareBinding(any());

        SseEventStreamService service = service();
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        assertThat(attemptReachedBinding.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        // Both registrations trigger an attempt (the first failed) — the
        // bridge self-heals instead of staying DOWN silently.
        verify(rabbitAdmin, timeout(5000).times(2)).declareQueue(any(Queue.class));
    }

    @Test
    void failedStartAfterDeclare_cleansUpOrphanQueue() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenAnswer(inv -> {
            Thread.sleep(50);
            failed.countDown();
            return ((Queue) inv.getArgument(0)).getName();
        });
        doThrow(new AmqpIllegalStateException("boom"))
            .when(rabbitAdmin).declareBinding(any());

        SseEventStreamService service = service();
        service.registerClient(new SseEmitter(0L), admin(), null, null, null);
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        org.mockito.ArgumentCaptor<String> nameCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rabbitAdmin, timeout(5000)).deleteQueue(nameCaptor.capture());
        assertThat(nameCaptor.getValue()).startsWith("zorrobpm.sse-bridge.");
    }
}
