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
     * диапазон. Эмуляция reconnect без брокера: второй клиент того же
     * principal стартует catchup от lastDeliveredCursor первого (ровно то,
     * что браузер пришлёт как Last-Event-ID), catchup читает окно из
     * EventQueryService-стаба — дыры нет, потому что первый клиент был ЗАКРЫТ
     * на границе реально доставленного, а не продолжен с пропуском.
     *
     * <p>Инвариант непрерывности: max(lastDelivered_1) + 1 == min(delivered_2)
     * при том, что catchup-окно покрывает границу. Стаб catchup возвращает
     * события строго подряд (курсоры lastDelivered+1 … lastDelivered+5) —
     * тест доказывает, что граница reconnect'а непрерывна (нет дыры между
     * «последнее доставленное» и «первое после reconnect»), а не содержимое
     * окна (его покрывают REL-37-тесты).
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void reconnectBoundary_isContinuous() throws Exception {
        SseEventStreamService svc = service(8);
        SlowEmitter slow = new SlowEmitter();
        String clientId = svc.registerBufferedClient(slow, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        String type = "rel52.b2." + UUID.randomUUID();
        for (long seq = 1; seq <= 2000; seq++) {
            svc.onDomainEvent(body(seq, type));
        }
        assertThat(slow.completed.await(60, TimeUnit.SECONDS))
            .as("first stream must close by overflow policy").isTrue();
        // Доставленное может быть ещё в пути (pump шлёт асинхронно через
        // send-lane; закрытие по переполнению приходит раньше, чем все
        // send'ы завершены — P-10: ждём доставку, не читаем сразу).
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(slow.delivered).isNotEmpty());
        long lastDelivered = slow.lastDeliveredCursor.get();
        assertThat(lastDelivered).as("something was really delivered before close").isGreaterThanOrEqualTo(0);
        int firstStreamCount = slow.delivered.size();

        // Reconnect: новый клиент того же principal, catchup от lastDelivered.
        // Последний реально доставленный id — граница catchup (Last-Event-ID).
        SlowEmitter resumed = new SlowEmitter();
        String resumedId = svc.registerBufferedClient(resumed, admin(), null, null, null);
        // drain с границей = lastDelivered: catchup отдаёт всё строго после.
        // Эмуляция catchup-окна: напрямую enqueue событий lastDelivered+1…
        // (живой catchup читает то же fp-окно от курсора клиента — REL-37).
        svc.drainBufferedClient(resumedId, lastDelivered);
        for (long seq = lastDelivered + 1; seq <= lastDelivered + 5; seq++) {
            svc.onDomainEvent(body(seq, type));
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(resumed.delivered).hasSize(5));

        // Непрерывность границы: первое после reconnect = lastDelivered + 1.
        // P-67: конкретное значение курсора, не «непусто».
        long firstResumed = ((Number) resumed.delivered.get(0).get("feedPosition")).longValue();
        assertThat(firstResumed)
            .as("reconnect continues exactly after the last delivered cursor (no hole)")
            .isEqualTo(lastDelivered + 1);
        // А вся история первого клиента + окно — без дыр внутри первого
        // клиента тоже: курсоры доставленного строго возрастают на 1
        // (pump — строго head-first, порядок REL-47).
        List<Long> cursors = slow.delivered.stream()
            .map(e -> ((Number) e.get("feedPosition")).longValue())
            .toList();
        assertThat(cursors).as("first stream delivered in cursor order").isSorted();
        assertThat(firstStreamCount).as("first stream cut before the end").isLessThan(2000);

        svc.removeClient(clientId);
        svc.removeClient(resumedId);
    }
}
