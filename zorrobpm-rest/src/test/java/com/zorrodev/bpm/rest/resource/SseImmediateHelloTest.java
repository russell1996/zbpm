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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-REL-70 (критерии 1, 2, 4): немедленный {@code :connected} после
 * регистрации SSE-клиента.
 *
 * <p>До фикса первый байт потока уходил только с первым heartbeat-тиком
 * (15с) или первым доменным событием — буферизующий прокси всё это время
 * держал handshake, EventSource висел в «pending». Фикс: приветствие идёт
 * через очередь writer'а (single-writer протокол REL-47 не нарушается —
 * никаких прямых {@code emitter.send} мимо pump'а) ДО чтения catchup и
 * drain, первый flush несёт байты за миллисекунды.
 *
 * <p>POF-мутации (критерий 4): убрать keep-ветку {@code hello} из
 * {@code drainToLive} — {@code hello_stagedPreDrain_pumpedFirst} КРАСНЫЙ
 * (приветствие съедено дедупом catchup как {@code cursor <= boundary});
 * убрать тело {@code sendImmediateHello} — тот же тест КРАСНЫЙ (тишина
 * после drain). Проводка контроллер→сервис — отдельно
 * {@code SseControllerHelloWiringTest} (P-46: delete строки в контроллере
 * иначе не краснит ничего).
 */
@ExtendWith(MockitoExtension.class)
class SseImmediateHelloTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        ReflectionTestUtils.setField(svc, "heartbeatIntervalMs", 60_000L);
        ReflectionTestUtils.setField(svc, "sendTimeoutMs", 60_000L);
        services.add(svc);
        return svc;
    }

    @AfterEach
    void tearDown() {
        for (SseEventStreamService svc : services) {
            try {
                svc.clearEventListeners();
                svc.stop();
            } catch (Exception ignore) {
            }
        }
        services.clear();
        org.slf4j.MDC.clear();
    }

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    /** Тот же перехват send, что в SseRel57HeartbeatTest: без сокета, с записью. */
    static class CapturingEmitter extends SseEmitter {
        final List<SseEventBuilder> sent = new CopyOnWriteArrayList<>();
        final AtomicLong lastWriteNanos = new AtomicLong(System.nanoTime());

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder);
            lastWriteNanos.set(System.nanoTime());
        }
    }

    /**
     * Критерий 2: wire-форма приветствия — голый SSE-комментарий
     * {@code :connected} (не диспатчит MessageEvent, фронт-кода не требует),
     * первым отправлением после drain.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hello_wireForm_isConnectedComment() throws Exception {
        SseEventStreamService svc = service();
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        svc.sendImmediateHello(clientId);
        svc.drainBufferedClient(clientId, 0L);

        await().atMost(Duration.ofSeconds(5))
            .until(() -> !emitter.sent.isEmpty());

        String wire = builderText(emitter.sent.get(0));
        assertThat(wire)
            .as("hello must serialize as a bare ':connected' SSE comment first")
            .startsWith(":connected\n");
        assertThat(wire)
            .as("hello must not look like a named event or data frame")
            .doesNotContain("event:")
            .doesNotContain("data:")
            .doesNotContain("id:");
    }

    /**
     * Критерии 1–2: приветствие, поставленное ДО drain, переживает
     * catchup-дедуп и уходит ровно один раз (не ноль — иначе прокси опять
     * ждёт; не два — иначе дубль первого байта).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hello_stagedPreDrain_pumpedFirst() {
        SseEventStreamService svc = service();
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

        svc.sendImmediateHello(clientId);
        // Catchup-граница 0: обычный staged live-эвент с cursor<=0 был бы
        // съеден дедупом — hello обязан пережить.
        svc.drainBufferedClient(clientId, 0L);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .until(() -> emitter.sent.size() >= 1 && pumpDrained(svc));
        assertThat(emitter.sent)
            .as("exactly one greeting must be pumped after drain (no silence, no double)")
            .hasSize(1);
    }

    /**
     * Контроль без приветствия: drain без hello — тишина (тест выше ловит
     * именно hello, а не «что-то вообще слалось»).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void noHello_drainSendsNothing() {
        SseEventStreamService svc = service();
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

        svc.drainBufferedClient(clientId, 0L);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .until(() -> pumpDrained(svc));
        assertThat(emitter.sent)
            .as("drain without hello must send nothing (the greeting above is the delta)")
            .isEmpty();
    }

    /**
     * Hello не стреляет в хук доставки доменных событий — наблюдаемость
     * {@code onEventSent} остаётся чистой (та же причина, что heartbeat,
     * SseRel57HeartbeatTest.heartbeat_neverNotifiesDeliveryHook).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hello_neverNotifiesDeliveryHook() {
        SseEventStreamService svc = service();
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

        List<Map<String, Object>> notified = new CopyOnWriteArrayList<>();
        svc.addEventListener((id, envelope) -> notified.add(envelope));

        svc.sendImmediateHello(clientId);
        svc.drainBufferedClient(clientId, 0L);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .until(() -> emitter.sent.size() >= 1 && pumpDrained(svc));
        assertThat(notified)
            .as("hello must never fire the domain-event delivery hook")
            .isEmpty();
    }

    /**
     * Hello при ПОЛНОЙ очереди пропускается, а не закрывает поток:
     * приветствие не несёт данных, терять из-за него стрим нельзя
     * (та же причина, что heartbeat_fullQueue_skipsWithoutClosing).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hello_fullQueue_skipsWithoutClosing() throws Exception {
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "perClientQueueEvents", 2);
        java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);
        SseEmitter blocking = new SseEmitter(60_000L) {
            @Override
            public void send(SseEventBuilder builder) throws java.io.IOException {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.send(builder);
            }
        };
        String clientId = svc.registerBufferedClient(blocking, admin(), null, null, null);
        Object writer = sessionById(svc, clientId);

        // Два live-события заполняют очередь cap=2 (первое уходит в pump и
        // виснет на латче, второе ждёт).
        enqueueOnWriter(svc, writer, 1L, "user-task.created");
        enqueueOnWriter(svc, writer, 2L, "user-task.created");
        await().atMost(Duration.ofSeconds(5)).until(() -> queueSizeOfWriter(writer) >= 1);

        // Hello при полной очереди: пропуск, поток жив.
        svc.sendImmediateHello(clientId);
        assertThat(sessionById(svc, clientId))
            .as("hello on a full queue must skip, not overflow-close the stream")
            .isNotNull();

        release.countDown();
    }

    /**
     * Best-effort: неизвестный clientId — тихий no-op, не исключение
     * (контроллер не должен ронять поток из-за приветствия).
     */
    @Test
    void hello_unknownClient_isNoop() {
        SseEventStreamService svc = service();
        svc.sendImmediateHello("no-such-client");
    }

    // ---- helpers (тот же reflective-стиль, что SseRel57HeartbeatTest) ----

    private static String builderText(SseEmitter.SseEventBuilder builder) throws Exception {
        var f = builder.getClass().getDeclaredField("sb");
        f.setAccessible(true);
        return f.get(builder).toString();
    }

    private static java.util.Collection<Object> sessionSnapshot(SseEventStreamService svc) throws Exception {
        var rf = SseEventStreamService.class.getDeclaredField("sessionRegistry");
        rf.setAccessible(true);
        Object registry = rf.get(svc);
        var m = registry.getClass().getMethod("snapshot");
        @SuppressWarnings("unchecked")
        java.util.Collection<Object> sessions = (java.util.Collection<Object>) m.invoke(registry);
        return sessions;
    }

    private static Object sessionById(SseEventStreamService svc, String clientId) throws Exception {
        for (Object writer : sessionSnapshot(svc)) {
            var idm = writer.getClass().getMethod("clientId");
            idm.setAccessible(true);
            if (clientId.equals(idm.invoke(writer))) {
                return writer;
            }
        }
        return null;
    }

    private static void enqueueOnWriter(SseEventStreamService svc, Object writer,
            long sequence, String type) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
            .id(String.valueOf(sequence))
            .name(type)
            .data(Map.of("sequence", sequence, "type", type))
            .reconnectTime(3000);
        var m = writer.getClass().getDeclaredMethod("enqueueLive",
            SseEmitter.SseEventBuilder.class, Map.class, long.class);
        m.setAccessible(true);
        m.invoke(writer, builder, Map.of("sequence", sequence, "type", type), sequence);
    }

    private static int queueSizeOfWriter(Object writer) throws Exception {
        var f = writer.getClass().getDeclaredField("queue");
        f.setAccessible(true);
        return ((java.util.ArrayDeque<?>) f.get(writer)).size();
    }

    /** Pump простаивает (очередь пуста и активного pump нет) — доставка осела. */
    private static boolean pumpDrained(SseEventStreamService svc) {
        try {
            for (Object writer : sessionSnapshot(svc)) {
                var qf = writer.getClass().getDeclaredField("queue");
                qf.setAccessible(true);
                if (!((java.util.ArrayDeque<?>) qf.get(writer)).isEmpty()) {
                    return false;
                }
                var pf = writer.getClass().getDeclaredField("pumpActive");
                pf.setAccessible(true);
                if ((boolean) pf.get(writer)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
