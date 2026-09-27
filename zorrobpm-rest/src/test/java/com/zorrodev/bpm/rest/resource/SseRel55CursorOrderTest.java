package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-REL-55 (NEW2-02 + NEW2-09): SSE курсоры строго возрастают + drop-head не тихий.
 *
 * <p>A1 — ровно сценарий аудита: seq 1 получает позицию 10 только на 4-м
 * чтении, seq 2 — позицию 11 сразу. До фикса один клиент получал курсоры
 * `[11, 10]` (нарушение "SSE ids must be non-decreasing": reconnect с
 * Last-Event-ID=11 терял событие 10 навсегда). После фикса — строго `[10, 11]`.
 *
 * <p>B1 — drop-head тихая дыра: мост рассылает курсоры `[10, 12]` (11
 * отброшена переполнением), LIVE-клиент закрывается ДО рассылки 12, так что
 * его Last-Event-ID остаётся на 10 и catchup на reconnect забирает дыру
 * честно. P-67: закрытие доказывается фактом закрытия + тем, что детектор
 * (12) закрытому клиенту НЕ доставлен.
 *
 * <p>Харнесс — тот же, что `SseRel52BridgeTest` (моки `EventQueryService`,
 * `CapturingEmitter`-стиль перехватчика, `drainBufferedClient(…, 0L)` для
 * перевода в LIVE).
 */
@ExtendWith(MockitoExtension.class)
class SseRel55CursorOrderTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(true);
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        services.add(svc);
        return svc;
    }

    @AfterEach
    void tearDown() {
        for (SseEventStreamService svc : services) {
            try {
                svc.clearEventListeners();
            } catch (Exception ignore) {
            }
        }
        services.clear();
        org.slf4j.MDC.clear();
    }

    private static Principal admin(UUID userId) {
        return new Principal.UserPrincipal(userId, "admin", "SUPER_ADMIN");
    }

    private static String body(long sequence, String type) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-25T00:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    static class CapturingEmitter extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        volatile boolean completed = false;

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        delivered.add((Map<String, Object>) envelope);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    /**
     * A1: смешанное быстрое/медленное разрешение — один клиент получает
     * курсоры строго по возрастанию. POF-мутация: рассылка сразу по
     * разрешении без сиквенсора (старый `dispatchDeferred` из retry-lane) —
     * этот тест КРАСНЫЙ (`[11, 10]`, isSorted падает).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void mixedFastSlowResolution_singleClientCursorsAreSorted() {
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 50L);

        // Сценарий аудита дословно: seq 1 → позиция 10 только на 4-м чтении,
        // seq 2 → позиция 11 сразу.
        AtomicInteger slowReads = new AtomicInteger(0);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(1L))
            .thenAnswer(inv -> {
                if (slowReads.incrementAndGet() >= 4) {
                    return java.util.Optional.of(10L);
                }
                return java.util.Optional.empty();
            });
        lenient().when(eventQueryService.resolveFeedPositionBySequence(2L))
            .thenReturn(java.util.Optional.of(11L));

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel55.a1." + UUID.randomUUID();
        svc.onDomainEvent(body(1L, type)); // медленная — голова сиквенсора
        svc.onDomainEvent(body(2L, type)); // быстрая — ждёт голову в сиквенсоре

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(2));
        List<Long> cursors = emitter.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue()).toList();
        assertThat(cursors)
            .as("аудит-сценарий [11, 10] обязан стать [10, 11] — курсоры строго возрастают")
            .containsExactly(10L, 11L);
        assertThat(slowReads.get())
            .as("медленная позиция реально ходила через defer-цикл, а не разрешилась с первого read")
            .isGreaterThanOrEqualTo(4);

        svc.removeClient(clientId);
    }

    /**
     * B1: разрыв курсоров (симуляция drop-head: `[10, 12]`) — LIVE-клиент
     * закрывается ДО рассылки детектора. POF-мутация: убрать
     * `closeLiveClientsForGap` — этот тест КРАСНЫЙ (клиент не закрыт и
     * получил `[10, 12]` молча).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void cursorGap_liveClientClosedBeforeDetectorDelivered() {
        SseEventStreamService svc = service();

        lenient().when(eventQueryService.resolveFeedPositionBySequence(10L))
            .thenReturn(java.util.Optional.of(10L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(12L))
            .thenReturn(java.util.Optional.of(12L));

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel55.b1." + UUID.randomUUID();
        svc.onDomainEvent(body(10L, type));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(1));

        // 11 в брокере отброшена (drop-head): мост видит сразу 12.
        svc.onDomainEvent(body(12L, type));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.completed).isTrue());
        assertThat(emitter.delivered)
            .as("закрытый клиент НЕ получает детектор 12 — его Last-Event-ID остаётся на 10, "
                + "catchup на reconnect забирает и 11, и 12 честно")
            .hasSize(1);
        assertThat(((Number) emitter.delivered.get(0).get("feedPosition")).longValue())
            .as("доставлен только прецедент 10")
            .isEqualTo(10L);

        svc.removeClient(clientId);
    }

    /**
     * B1-допуск: БЕЗ разрыва ничего не закрывается — gap-close не стреляет
     * на нормальном потоке (иначе это шторм переподключений, а не фикс).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void contiguousCursors_noClientClosed() {
        SseEventStreamService svc = service();

        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel55.b1neg." + UUID.randomUUID();
        svc.onDomainEvent(body(20L, type));
        svc.onDomainEvent(body(21L, type));
        svc.onDomainEvent(body(22L, type));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(3));
        assertThat(emitter.completed)
            .as("смежный поток никого не закрывает")
            .isFalse();
        assertThat(emitter.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue()).toList())
            .containsExactly(20L, 21L, 22L);

        svc.removeClient(clientId);
    }
}
