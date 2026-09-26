package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-PERF-6: SSE slow client must not block, maxClients -> 429, UUID outside loop, cursor catchup.
 */
@ExtendWith(MockitoExtension.class)
class SsePerf6IntegrationTest {

    @Mock EventAuthzResolver eventAuthzResolver;

    private SseEventStreamService service() {
        when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        // WO-REL-38: live-мост резолвит позицию по sequence — строка "назначена".
        // lenient: maxClients-тест onDomainEvent не зовёт (стаб ему не нужен).
        com.zorrodev.bpm.engine.service.EventQueryService queries =
            mock(com.zorrodev.bpm.engine.service.EventQueryService.class);
        org.mockito.Mockito.lenient()
            .when(queries.resolveFeedPositionBySequence(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        // WO-SEC-67: +2 ctor args (UiUserLookupService, ApiKeyRepository) — null = no live checks in unit scope.
        return new SseEventStreamService(queries, eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
    }

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    static class SlowEmitter extends SseEmitter {
        final long delayMs;
        SlowEmitter(long delayMs) { super(0L); this.delayMs = delayMs; }
        // WO-OPS-14: намеренно Thread.sleep, не Awaitility — это НЕ ожидание
        // условия, а симуляция медленного клиента (часть тестируемого поведения:
        // send() обязан занимать delayMs, иначе backpressure-тест бессмысленен).
        @Override public void send(SseEventBuilder builder) throws IOException {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            super.send(builder);
        }
    }

    @Test
    void slowClient_doesNotBlockOthers() throws Exception {
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "sendTimeoutMs", 500L);

        SseEmitter fast = new SseEmitter(0L);
        List<Map<String, Object>> fastEvents = new CopyOnWriteArrayList<>();
        svc.addEventListener((cid, env) -> {
            // only fast client's listener would be called via envelope, but we track via onDomainEvent timing
        });

        SlowEmitter slow = new SlowEmitter(3000L);
        CountDownLatch fastReceived = new CountDownLatch(1);

        // fast emitter that counts latch
        SseEmitter fastCounting = new SseEmitter(0L) {
            @Override public void send(SseEventBuilder builder) throws IOException {
                super.send(builder);
                fastReceived.countDown();
            }
        };

        svc.registerClient(slow, admin(), null, null, null);
        svc.registerClient(fastCounting, admin(), null, null, null);

        long start = System.nanoTime();
        svc.onDomainEvent("{\"sequence\":1,\"type\":\"test\",\"processDefinitionId\":\"" + UUID.randomUUID() + "\",\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"id\":\"" + UUID.randomUUID() + "\"}");
        boolean got = fastReceived.await(2, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertThat(got).as("fast client must receive within 2s even though slow sleeps 3s").isTrue();
        assertThat(elapsed).as("fan-out must not wait for slow client (would be >2500ms)").isLessThan(1500);
    }

    @Test
    void maxClients_exceeded_returns429() {
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "maxClients", 2);

        svc.registerClient(new SseEmitter(0L), admin(), null, null, null);
        svc.registerClient(new SseEmitter(0L), admin(), null, null, null);

        assertThatThrownBy(() -> svc.registerClient(new SseEmitter(0L), admin(), null, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode().value()).isEqualTo(429));
    }
}
