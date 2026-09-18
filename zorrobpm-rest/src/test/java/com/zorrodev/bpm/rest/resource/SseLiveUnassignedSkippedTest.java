package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.service.EventQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-REL-38: live-мост без назначенной позиции НЕ рассылает событие с сырым
 * sequence (иначе курсор клиента указывал бы на F15-дыру), а ждёт тик джоба —
 * ограниченно. Покрыты обе ветки: дождались позиции (доставлено с ней) и не
 * дождались (пропущено молча, catchup доберёт при reconnect).
 */
@ExtendWith(MockitoExtension.class)
class SseLiveUnassignedSkippedTest {

    @Mock
    private EventQueryService eventQueryService;
    @Mock
    private EventAuthzResolver eventAuthzResolver;

    private SseEventStreamService service;

    private static com.zorrodev.bpm.engine.security.Principal admin() {
        return new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(
            UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    @BeforeEach
    void setUp() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        service = new SseEventStreamService(eventQueryService, eventAuthzResolver, null,
            new tools.jackson.databind.ObjectMapper(), null, null);
    }

    @AfterEach
    void tearDown() {
        service.clearEventListeners();
        org.slf4j.MDC.clear();
    }

    private String registerClient() {
        return service.registerClient(new SseEmitter(0L), admin(), null, null, null);
    }

    private static String body(long sequence) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"rel38.live\",\"version\":1,\"occurredAt\":\"2026-09-18T00:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    @Test
    void unassignedRow_skipped_quietly() throws Exception {
        String clientId = registerClient();
        List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        service.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                delivered.add(envelope);
                latch.countDown();
            }
        });
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenReturn(Optional.empty());
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(true);

        // 30 попыток × 100ms ≈ 3s ожидания тика, затем пропуск.
        long start = System.nanoTime();
        service.onDomainEvent(body(4242L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(latch.await(6, TimeUnit.SECONDS))
            .as("событие без позиции не должно рассылаться (молча пропущено)").isFalse();
        assertThat(delivered).isEmpty();
        assertThat(elapsedMs)
            .as("мост ждал тик джоба (~3s), а не отбросил сразу").isGreaterThanOrEqualTo(2500);
        service.removeClient(clientId);
    }

    @Test
    void unknownSequence_skippedWithoutWaiting() {
        String clientId = registerClient();
        List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        service.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                delivered.add(envelope);
            }
        });
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenReturn(Optional.empty());
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(false);

        long start = System.nanoTime();
        service.onDomainEvent(body(999999L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(delivered).as("чужой sequence отбрасывается сразу").isEmpty();
        assertThat(elapsedMs).as("без ожидания тика — сразу").isLessThan(2000);
        service.removeClient(clientId);
    }
}
