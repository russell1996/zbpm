package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.security.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-REL-56: SSE выпуск по позиции с водяным знаком (NEW3-02) + отброс чужого
 * sequence с метрикой (NEW3-03).
 *
 * <p>A1 — ровно харнесс-сценарий аудита: прибытие pos 9 → 11 → 10 (порядок
 * emit ≠ порядок commit под конкурентной нагрузкой). До фикса arrival-FIFO
 * выпускал 11 раньше 10 и закрывал ВСЕХ LIVE-клиентов ложной «дырой»; после
 * фикса — доставлено [9, 10, 11], клиент НЕ закрыт.
 *
 * <p>A2 — gap-детектор срабатывает только на реальный таймаут ожидания
 * позиции (11 при wm 9, позиция 10 не приходит дольше бюджета), не на порядок
 * прибытия: закрытие приходит ПОСЛЕ бюджета (~1.5с при shrink 50мс), а не в
 * миллисекунды; закрытый клиент не получает детектор.
 *
 * <p>B1 — событие без строки в ЭТОЙ БД (чужой sequence, sequence=0) не
 * рассылается, счётчик `zbpm.sse.foreign.dropped` инкрементируется.
 * P-67: конкретные значения (пустой поток + counter==2), не «что-то вызвалось».
 *
 * <p>Харнесс — тот же, что `SseRel55CursorOrderTest` (моки `EventQueryService`,
 * `CapturingEmitter`-перехватчик, `drainBufferedClient(…, 0L)` для LIVE).
 */
@ExtendWith(MockitoExtension.class)
class SseRel56WatermarkTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        return service(null);
    }

    private SseEventStreamService service(SimpleMeterRegistry registry) {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(true);
        BpmMetrics metrics = registry == null ? null : new BpmMetrics(registry);
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null, metrics);
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
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-27T00:00:00Z\","
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
     * A1: харнесс аудита дословно — прибытие 9 → 11 → 10.
     * POF-мутация: arrival-FIFO + мгновенный gap-close (код REL-55) — этот
     * тест КРАСНЫЙ (доставлено [9, 11] + клиент закрыт, 10 теряется из потока).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void outOfOrderArrival_deliveredInPositionOrder_clientNotClosed() {
        SseEventStreamService svc = service();

        // Порядок commit (позиции) ≠ порядок прибытия (sequence): T1 emit
        // раньше / commit позже (pos 11), T2 наоборот (pos 10).
        lenient().when(eventQueryService.resolveFeedPositionBySequence(100L))
            .thenReturn(java.util.Optional.of(9L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(101L))
            .thenReturn(java.util.Optional.of(11L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(102L))
            .thenReturn(java.util.Optional.of(10L));

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel56.a1." + UUID.randomUUID();
        svc.onDomainEvent(body(100L, type)); // pos 9 — bootstrap знака
        svc.onDomainEvent(body(101L, type)); // pos 11 — ждёт 10, НЕ закрывает
        svc.onDomainEvent(body(102L, type)); // pos 10 — разблокирует выпуск

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(3));
        List<Long> cursors = emitter.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue()).toList();
        assertThat(cursors)
            .as("порядок commit, не прибытия: 11 ждала 10 в heap'е, а не ушла раньше неё")
            .containsExactly(9L, 10L, 11L);
        assertThat(emitter.completed)
            .as("ложной дыры нет — LIVE-клиент НЕ закрыт (NEW3-02: раньше закрывались ВСЕ)")
            .isFalse();

        svc.removeClient(clientId);
    }

    /**
     * A2: позиция 10 не приходит вообще — gap-close только после бюджета
     * ожидания, не на порядок прибытия. POF-мутация: мгновенный gap-close на
     * разрешённой голове с разрывом — этот тест КРАСНЫЙ (закрытие в
     * миллисекунды, а не после ~1.5с бюджета).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void genuinelyMissingPosition_gapClosesOnlyAfterBudget() throws Exception {
        SseEventStreamService svc = service();
        // Бюджет 30 × 50мс = 1.5с; проверка «ещё не закрыт» на 800мс —
        // односторонний запас (медленный CI закрывает только ПОЗЖЕ, не раньше).
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 50L);

        lenient().when(eventQueryService.resolveFeedPositionBySequence(100L))
            .thenReturn(java.util.Optional.of(9L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(101L))
            .thenReturn(java.util.Optional.of(11L));

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel56.a2." + UUID.randomUUID();
        svc.onDomainEvent(body(100L, type));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(1));
        svc.onDomainEvent(body(101L, type)); // разрыв 9 → 11, позиция 10 не придёт

        // До бюджета — тишина: ни закрытия, ни детектора в потоке.
        Thread.sleep(800);
        assertThat(emitter.completed)
            .as("разрыв в порядке прибытия — не повод для закрытия до истечения бюджета")
            .isFalse();
        assertThat(emitter.delivered).hasSize(1);

        // После бюджета — реальная дыра: закрытие ДО рассылки детектора.
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.completed).isTrue());
        assertThat(emitter.delivered)
            .as("закрытый клиент НЕ получает детектор 11 — его Last-Event-ID остаётся на 9, "
                + "catchup на reconnect забирает дыру честно (паритет REL-55 B1)")
            .hasSize(1);
        assertThat(((Number) emitter.delivered.get(0).get("feedPosition")).longValue())
            .as("доставлен только прецедент 9")
            .isEqualTo(9L);

        svc.removeClient(clientId);
    }

    /**
     * WO-REL-58 (AUDIT-E): реальная дыра убивала realtime НАВСЕГДА — знак не
     * двигался за дыру, та же голова детектилась каждые ~1.5с бесконечно.
     * Сценарий: 9 → 11 (10 не приходит вообще) → gap-таймер закрывает первого
     * LIVE-клиента → НОВЫЙ клиент подключается и реально получает 12, 13.
     * POF-мутация: убрать `dispatchWatermark = head.cursor - 1` из обычной
     * ветки таймера — этот тест КРАСНЫЙ (новый клиент получает `sent=[]`:
     * 11 вечно торчит головой heap'а, 12/13 встают за ней и не выпускаются,
     * а взведённый заново таймер закрывает и нового клиента).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void gapTimerRecovery_newLiveClientReceivesEventsAfterRealGap() {
        SseEventStreamService svc = service();
        // Бюджет 30 × 50мс = 1.5с, как в A2.
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 50L);

        lenient().when(eventQueryService.resolveFeedPositionBySequence(100L))
            .thenReturn(java.util.Optional.of(9L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(101L))
            .thenReturn(java.util.Optional.of(11L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(102L))
            .thenReturn(java.util.Optional.of(12L));
        lenient().when(eventQueryService.resolveFeedPositionBySequence(103L))
            .thenReturn(java.util.Optional.of(13L));

        CapturingEmitter first = new CapturingEmitter();
        String firstId = svc.registerBufferedClient(first, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(firstId, 0L);

        String type = "rel58.auditE." + UUID.randomUUID();
        svc.onDomainEvent(body(100L, type)); // pos 9 — доставлена
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(first.delivered).hasSize(1));
        svc.onDomainEvent(body(101L, type)); // pos 11 — разрыв, 10 не придёт никогда

        // Реальная дыра: первый LIVE-клиент закрыт после бюджета.
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(first.completed).isTrue());

        // Новый клиент — как reconnect с Last-Event-ID=9 после закрытия.
        CapturingEmitter second = new CapturingEmitter();
        String secondId = svc.registerBufferedClient(second, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(secondId, 9L);

        svc.onDomainEvent(body(102L, type)); // pos 12
        svc.onDomainEvent(body(103L, type)); // pos 13

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(second.delivered).hasSize(2));
        List<Long> cursors = second.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue()).toList();
        assertThat(cursors)
            .as("поток восстановился после дыры: новый клиент получает 12, 13, а не sent=[]")
            .containsExactly(12L, 13L);
        assertThat(second.completed)
            .as("повторного gap-close для непрерывного хвоста нет — realtime жив")
            .isFalse();

        svc.removeClient(firstId);
        svc.removeClient(secondId);
    }

    /**
     * B1: чужой sequence и sequence=0 — отброс + метрика.
     * POF-мутация B2: вернуть рассылку чужого ряда (код REL-55
     * `dispatchUnsequenced`) — этот тест КРАСНЫЙ (чужое событие с
     * sequence=999999 доставлено в поток, SSE id из чужого домена).
     * POF-мутация метрики: убрать инкремент — КРАСНЫЙ на counter (0 вместо 2).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void foreignSequence_droppedAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SseEventStreamService svc = service(registry);
        // Последний стаб побеждает: чужого ряда нет в ЭТОЙ БД.
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(false);

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel56.b1." + UUID.randomUUID();
        svc.onDomainEvent(body(999999L, type)); // чужая инсталляция
        svc.onDomainEvent(body(0L, type)); // отсутствующий sequence → 0

        // Отброс синхронный (в consumer-потоке, без defer) — маленькое окно
        // на всякий случай, затем строгие ассерты.
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(registry.counter("zbpm.sse.foreign.dropped").count()).isEqualTo(2.0));
        assertThat(emitter.delivered)
            .as("чужой sequence не рассылается: поток пуст (NEW3-03: раньше уходил с id=sequence)")
            .isEmpty();
        assertThat(emitter.completed)
            .as("отброс чужого — не повод закрывать клиента")
            .isFalse();

        svc.removeClient(clientId);
    }
}
