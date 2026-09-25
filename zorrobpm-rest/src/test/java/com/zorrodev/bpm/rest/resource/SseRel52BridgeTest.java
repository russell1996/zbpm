package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * WO-REL-52, часть A: мост.
 *
 * <p>A2: liveness — раз на пользователя за событие (группировка), не раз на
 * клиента. A3: resolveLiveCursor не блокирует consumer-поток моста.
 * A1-harness (500 клиентов × 50 событий/с, числа ДО/ПОСЛЕ) — в отчёте
 * (живой прогон этого же класса с подсчётом SQL через Hibernate-статистику
 * невозможен в unit-scope без БД — A1 доказан full-context IT
 * SseRel52BridgePgIT + числами в отчёте; здесь — механизм).
 */
@ExtendWith(MockitoExtension.class)
class SseRel52BridgeTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
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

    private static String bodyWithPd(long sequence, String type, UUID pdId) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-25T00:00:00Z\","
            + "\"processDefinitionId\":\"" + pdId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    static class CapturingEmitter extends SseEmitter {
        final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();

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
    }

    /**
     * A2 (механизм, unit-уровень): liveness — ОДИН row-read на пользователя за
     * событие, а не один на клиента. 10 вкладок одного юзера + 5 другого →
     * ровно 2 вызова securityState на событие, при этом доставка — всем 15
     * (группировка не теряет клиентов). P-67: точное число вызовов, не
     * «что-то вызвалось».
     *
     * <p>POF-мутация: убрать группировку (per-client isCredentialLive в цикле)
     * — этот тест КРАСНЫЙ (15 вызовов вместо 2).
     */
    @Test
    void livenessCheck_groupedByUser_oneLookupPerUserPerEvent() {
        UiUserLookupService lookup = org.mockito.Mockito.mock(UiUserLookupService.class);
        ApiKeyService apiKeys = org.mockito.Mockito.mock(ApiKeyService.class);
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        lenient().when(lookup.securityState(userA)).thenReturn(java.util.Optional.of(
            new UiUserLookupService.UserSecurityState(userA, "a", "SUPER_ADMIN", true, 7, false)));
        lenient().when(lookup.securityState(userB)).thenReturn(java.util.Optional.of(
            new UiUserLookupService.UserSecurityState(userB, "b", "SUPER_ADMIN", true, 7, false)));
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), lookup, apiKeys);
        services.add(svc);

        List<CapturingEmitter> emitters = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 10; i++) {
            CapturingEmitter e = new CapturingEmitter();
            emitters.add(e);
            String id = svc.registerBufferedClient(e,
                new Principal.UserPrincipal(userA, "a", "SUPER_ADMIN"), null, null, null);
            svc.drainBufferedClient(id, 0L);
        }
        for (int i = 0; i < 5; i++) {
            CapturingEmitter e = new CapturingEmitter();
            emitters.add(e);
            String id = svc.registerBufferedClient(e,
                new Principal.UserPrincipal(userB, "b", "SUPER_ADMIN"), null, null, null);
            svc.drainBufferedClient(id, 0L);
        }
        // Регистрационные lookups (currentTokenVersion) — не в счёт: измеряем
        // стоимость ОДНОГО события.
        org.mockito.Mockito.clearInvocations(lookup);

        svc.onDomainEvent(body(1L, "rel52.a2." + UUID.randomUUID()));

        for (CapturingEmitter e : emitters) {
            await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(e.delivered).hasSize(1));
        }
        verify(lookup, times(1)).securityState(userA);
        verify(lookup, times(1)).securityState(userB);
        verify(lookup, times(2)).securityState(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Verifier HOLD-1 (WO-REL-52): deferred-путь обязан доставлять
     * restricted-клиенту так же, как live-путь. Restricted-принципал
     * (allowedPdIds={P}, не SUPER_ADMIN) + событие с processDefinitionId=P,
     * чья позиция появляется поздно → рассылка идёт через retry-lane.
     * Live-эквивалент (позиция сразу) доставляется; deferred обязан тоже.
     *
     * <p>POF-мутация: pdUuid=null в dispatchDeferred — этот тест КРАСНЫЙ
     * (отложенное событие тихо пропускается: effective={P} не содержит null).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void deferredDispatch_restrictedClient_receivesLikeLive() {
        UUID pdId = UUID.randomUUID();
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any()))
            .thenReturn(java.util.List.of(pdId));
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(true);
        AtomicInteger reads = new AtomicInteger(0);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(9L))
            .thenAnswer(inv -> {
                if (reads.incrementAndGet() >= 6) {
                    return java.util.Optional.of(9L);
                }
                return java.util.Optional.empty();
            });
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        services.add(svc);
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 50L);

        Principal restricted = new Principal.UserPrincipal(UUID.randomUUID(), "op", "USER");
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, restricted, null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel52.hold1." + UUID.randomUUID();
        svc.onDomainEvent(bodyWithPd(9L, type, pdId)); // позиции нет — уходит в defer

        // Отложенная доставка restricted-клиенту (позиция разрешается на
        // ~6-й попытке ≈ 300мс; окно 10с — без запаса на флейки, P-10).
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(1));
        assertThat(((Number) emitter.delivered.get(0).get("feedPosition")).longValue())
            .as("deferred event delivered with its resolved cursor")
            .isEqualTo(9L);
        assertThat(reads.get())
            .as("delivery really went through the defer cycle, not the first read")
            .isGreaterThanOrEqualTo(6);

        svc.removeClient(clientId);
    }

    /**
     * A3: одно "медленное" разрешение курсора (позиция появляется только
     * через ~2с) НЕ задерживает доставку другим клиентам: второе событие
     * (позиция готова сразу) доставляется за миллисекунды, пока первое ещё
     * ждёт в retry-lane. До фикса onDomainEvent спал 30×100мс в
     * consumer-потоке — второе событие ждало бы все 2с.
     *
     * <p>POF-мутация: вернуть sleep-цикл в resolveLiveCursor — этот тест
     * КРАСНЫЙ (fast-delivery превышает 1.5с).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void slowCursorResolution_doesNotBlockOtherClients() throws Exception {        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 50L);

        // Позиция seq=1 появляется только после ~1с (медленный assigner —
        // DEFERRED-бюджет 30 попыток × 50мс = 1.5с окно, разрешение на 20-й
        // попытке ≈ 1с; запас до бюджета двукратный, флейки-порога нет);
        // seq=2 — сразу. Различаем по sequence.
        AtomicInteger slowReads = new AtomicInteger(0);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(1L))
            .thenAnswer(inv -> {
                // 20 попыток × 50мс ≈ 1с (defer-цикл ходит каждые 50мс —
                // см. deferredCursorDelayMs ниже; +1 начальный read).
                if (slowReads.incrementAndGet() >= 20) {
                    return java.util.Optional.of(1L);
                }
                return java.util.Optional.empty();
            });

        CapturingEmitter slow = new CapturingEmitter();
        CapturingEmitter fast = new CapturingEmitter();
        String slowId = svc.registerBufferedClient(slow, admin(UUID.randomUUID()), null, null, null);
        String fastId = svc.registerBufferedClient(fast, admin(UUID.randomUUID()), null, null, null);
        svc.drainBufferedClient(slowId, 0L);
        svc.drainBufferedClient(fastId, 0L);
        List<String> sentTo = new CopyOnWriteArrayList<>();
        svc.addEventListener((cid, envelope) -> sentTo.add(cid));

        String type = "rel52.a3." + UUID.randomUUID();
        long slowStart = System.nanoTime();
        svc.onDomainEvent(body(1L, type)); // медленная позиция — уходит в defer
        long deferReturnMs = (System.nanoTime() - slowStart) / 1_000_000;
        // Consumer-поток вернулся СРАЗУ (не спал 2с в resolveLiveCursor).
        assertThat(deferReturnMs)
            .as("onDomainEvent with unpositioned sequence must return without sleeping")
            .isLessThan(1500);

        // Быстрое событие — доставляется немедленно, не ждёт медленное.
        long fastStart = System.nanoTime();
        svc.onDomainEvent(body(2L, type));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(fast.delivered).hasSize(1));
        long fastMs = (System.nanoTime() - fastStart) / 1_000_000;
        assertThat(fastMs)
            .as("fast event must not wait behind the slow cursor resolution")
            .isLessThan(1500);

        // Медленное — доставляется позже через retry-lane (та же семантика):
        // позиция seq=1 разрешается на 40-й попытке (~2с), доставка идёт
        // сразу после — ждём сначала доставку (событие), затем порог попыток
        // (механизм). Доставка доказывает разрешение; порог — что defer-цикл
        // реально ходил, а не «повезло с первым read».
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(slow.delivered).hasSizeGreaterThanOrEqualTo(1));
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(slowReads.get()).isGreaterThanOrEqualTo(20));
        assertThat(slow.delivered).isNotEmpty();

        svc.removeClient(slowId);
        svc.removeClient(fastId);
    }
}
