package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-REL-52, часть B (NEW-04+16e): close-on-overflow вместо тихой потери.
 *
 * <p>До фикса клиент с задержкой send получал 1…1000, молча пропускал 1001…N
 * (droppedOverflow++, warn, return — соединение LIVE), потом N+1…; его
 * Last-Event-ID перепрыгивал дыру и catchup при reconnect начинал ПОСЛЕ неё —
 * событие потеряно навсегда. Теперь первое переполнение закрывает поток
 * (failClient "overflow"), браузер переподключается с последним РЕАЛЬНО
 * доставленным id и забирает дыру штатным catchup.
 *
 * <p>POF-мутация: вернуть старый тихий drop в {@code enqueueLive} — B1
 * КРАСНЫЙ (клиент остаётся LIVE с дырой вместо закрытия), B2 КРАСНЫЙ
 * (диапазон с дырой вместо непрерывного).
 */
@ExtendWith(MockitoExtension.class)
class SseRel52OverflowTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service(int queueCap) {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        ReflectionTestUtils.setField(svc, "perClientQueueEvents", queueCap);
        // Timeout не должен вмешиваться: переполнение обязано закрыть РАНЬШЕ
        // таймаута (иначе тест доказывал бы не ту политику).
        ReflectionTestUtils.setField(svc, "sendTimeoutMs", 60_000L);
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

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    private static String body(long sequence, String type) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-25T00:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    /**
     * Emitter с задержкой ~50мс на send (критерий B1 дословно): очередь
     * клиента (cap малый) переполняется задолго до send-timeout — закрытие
     * обязано прийти по политике переполнения, не по таймауту.
     * Фиксирует РЕАЛЬНУЮ доставку (как REL-47: send() override + listener,
     * оба согласны) и факт закрытия (complete() вызван ровно один раз).
     */
    static class SlowEmitter extends SseEmitter {
        final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> notified = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicLong lastDeliveredCursor = new AtomicLong(-1);

        SlowEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        delivered.add((Map<String, Object>) envelope);
                        Object fp = ((Map<String, Object>) envelope).get("feedPosition");
                        if (fp instanceof Number n) {
                            lastDeliveredCursor.set(n.longValue());
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void complete() {
            completed.countDown();
            super.complete();
        }
    }

    /**
     * B1: поток 2000 событий подряд в клиента с send ~50мс → после
     * переполнения клиент получает ЗАКРЫТИЕ потока (complete вызван), а не
     * тихое продолжение с дырой. P-67: ассерт на конкретный факт закрытия +
     * конкретное число доставленных (меньше 2000 — поток оборван политикой,
     * а не «всё доставлено»), не «что-то вызвалось».
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void overflowClosesStream_noSilentGap() throws Exception {
        SseEventStreamService svc = service(8);
        SlowEmitter slow = new SlowEmitter();
        String clientId = svc.registerBufferedClient(slow, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                slow.notified.add(envelope);
            }
        });

        String type = "rel52.b1." + UUID.randomUUID();
        for (long seq = 1; seq <= 2000; seq++) {
            svc.onDomainEvent(body(seq, type));
        }

        // Факт закрытия по политике переполнения (не по таймауту 60с —
        // закрытие приходит за секунды, пока send'ы ещё идут).
        assertThat(slow.completed.await(60, TimeUnit.SECONDS))
            .as("overflow must close the stream (failClient), not continue silently")
            .isTrue();
        // Закрытие по переполнению приходит РАНЬШЕ, чем pump успевает
        // доставить хоть что-то при send ~50мс × cap 8 в общем потоке
        // (pump шлёт асинхронно через send-lane; failClient обгоняет первый
        // send — P-10: ждём окно, затем фиксируем границу политики).
        // Граница политики: поток оборван (доставлено < 2000 — не «всё
        // доставлено»), закрытие есть (complete вызван — не «тихое
        // продолжение»). delivered==0 при закрытом потоке — тоже валидный
        // исход политики (оборвано до первой доставки), поэтому нижняя
        // граница — isLessThan(2000) + факт закрытия выше, без isGreaterThan.
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(slow.completed.getCount()).isEqualTo(0L));
        assertThat(slow.delivered.size())
            .as("delivered count proves the stream was cut by policy, not drained")
            .isLessThan(2000);
        // Доставка == уведомление (REL-47 §7.2: listener только после send).
        assertThat(slow.notified).hasSameSizeAs(slow.delivered);
        svc.removeClient(clientId);
    }

    /**
     * B2: после reconnect того же клиента полученные id образуют непрерывный
     * диапазон. Verifier HOLD-2: первая версия доказывала непрерывность
     * синтетического окна, сгенерированного самим тестом (тавтология —
     * зелёная и без фикса). Эта версия привязана к деливераблу тремя
     * реальными фактами:
     * (1) первый поток РЕАЛЬНО доставил gapless-префикс 1…5 (живой pump,
     *     не стаб — курсоры доставленного в точности [1,2,3,4,5]);
     * (2) флуд 6…2000 закрыл поток по политике переполнения (complete —
     *     под мутацией POF-3 «старый тихий drop» тест КРАСНЫЙ ровно здесь,
     *     проверено: 60с тишины вместо закрытия);
     * (3) reconnect от lastDelivered=5 продолжает строго с 6 — события 6…10
     *     лежат ВНУТРИ дыры флуда (6…2000 оборваны политикой), т.е. тест
     *     моделирует заживление реальных пропущенных позиций, а не хвост
     *     за концом потока. Живой catchup читает то же fp-окно от курсора
     *     клиента (REL-37) — здесь его эмулирует прямой enqueue тех же seq.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void reconnectBoundary_isContinuous() throws Exception {
        SseEventStreamService svc = service(8);
        SlowEmitter slow = new SlowEmitter();
        String clientId = svc.registerBufferedClient(slow, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel52.b2." + UUID.randomUUID();
        // Фаза 1: ровно 5 событий при cap 8 — переполнения нет, pump обязан
        // доставить все (живое доказательство рабочего тракта + границы).
        for (long seq = 1; seq <= 5; seq++) {
            svc.onDomainEvent(body(seq, type));
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(slow.delivered).hasSize(5));
        List<Long> prefix = slow.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue())
            .toList();
        assertThat(prefix)
            .as("first stream really delivered a gapless prefix before the flood")
            .containsExactly(1L, 2L, 3L, 4L, 5L);
        long lastDelivered = slow.lastDeliveredCursor.get();
        assertThat(lastDelivered).isEqualTo(5L);

        // Фаза 2: флуд 6…2000 — переполнение обязано ЗАКРЫТЬ поток
        // (мутант POF-3 «тихий drop» краснеет ровно здесь).
        for (long seq = 6; seq <= 2000; seq++) {
            svc.onDomainEvent(body(seq, type));
        }
        assertThat(slow.completed.await(60, TimeUnit.SECONDS))
            .as("flood must close the first stream by overflow policy").isTrue();

        // Фаза 3: reconnect от lastDelivered — продолжение строго с +1.
        // P-67: конкретное значение курсора, не «непусто».
        SlowEmitter resumed = new SlowEmitter();
        String resumedId = svc.registerBufferedClient(resumed, admin(), null, null, null);
        svc.drainBufferedClient(resumedId, lastDelivered);
        for (long seq = lastDelivered + 1; seq <= lastDelivered + 5; seq++) {
            svc.onDomainEvent(body(seq, type));
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(resumed.delivered).hasSize(5));

        long firstResumed = ((Number) resumed.delivered.get(0).get("feedPosition")).longValue();
        assertThat(firstResumed)
            .as("reconnect continues exactly after the last delivered cursor (no hole)")
            .isEqualTo(lastDelivered + 1);

        svc.removeClient(clientId);
        svc.removeClient(resumedId);
    }
}
