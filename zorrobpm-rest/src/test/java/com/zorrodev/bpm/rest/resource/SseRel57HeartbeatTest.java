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
 * WO-REL-57 (критерии 1, 2, 5): SSE-heartbeat держит idle-поток живым.
 *
 * <p>Живой прод-репорт 2026-09-27: тихий (без событий) SSE-поток рвался на
 * промежуточном прокси ({@code ERR_INCOMPLETE_CHUNKED_ENCODING}) — сервис не
 * слал никакого keep-alive. Фикс: периодический SSE-комментарий
 * ({@code :heartbeat}) каждому LIVE-клиенту через его же writer-очередь.
 *
 * <p>POF-мутация (критерий 5): убрать {@code ensureHeartbeat()} из
 * {@code registerClientInternal} — {@code idleStream_survivesProxyTimeout}
 * КРАСНЫЙ (тиков нет, эмулированный прокси рвёт поток за свой таймаут),
 * остальные — зелёные (форма/нотификация не зависят от арминга).
 */
@ExtendWith(MockitoExtension.class)
class SseRel57HeartbeatTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service(long heartbeatIntervalMs) {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        ReflectionTestUtils.setField(svc, "heartbeatIntervalMs", heartbeatIntervalMs);
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

    /**
     * Эмуляция прокси с idle-timeout: каждый send обновляет метку, разрыв
     * фиксируется проверкой свежести (без фоновых потоков — детерминировано).
     */
    static class ProxyEmulatingEmitter extends SseEmitter {
        final List<SseEventBuilder> sent = new CopyOnWriteArrayList<>();
        final AtomicLong lastWriteNanos = new AtomicLong(System.nanoTime());

        ProxyEmulatingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder);
            lastWriteNanos.set(System.nanoTime());
        }

        boolean idleForLongerThan(long idleTimeoutMs) {
            return System.nanoTime() - lastWriteNanos.get()
                > TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
        }
    }

    /**
     * Критерий 1+5: idle-поток без событий НЕ рвётся прокси с коротким
     * read-timeout — heartbeat держит его живым. POF: без арминга тиков
     * (откат {@code ensureHeartbeat()}) поток протухает за таймаут прокси и
     * тест КРАСНЫЙ.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void idleStream_survivesProxyTimeout() {
        SseEventStreamService svc = service(50L);
        ProxyEmulatingEmitter emitter = new ProxyEmulatingEmitter();
        String clientId = svc.registerClient(emitter, admin(), null, null, null);
        assertThat(clientId).isNotNull();

        // Прокси с read-timeout 200мс (в 4 раза больше интервала тиков 50мс —
        // запас против timing-флейка, P-10); полсекунды тишины БЕЗ heartbeat
        // порвали бы поток дважды.
        await().during(Duration.ofMillis(500)).pollInterval(Duration.ofMillis(50))
            .until(() -> !emitter.idleForLongerThan(200L));

        // Тики реально слались (не "прокси просто не проверял"): минимум два
        // heartbeat за 500мс при интервале 50мс — с запасом на джиттер.
        long heartbeats = emitter.sent.stream().filter(b -> isHeartbeat(b)).count();
        assertThat(heartbeats)
            .as("heartbeat ticks must actually be sent on an idle stream")
            .isGreaterThanOrEqualTo(2);
    }

    /**
     * Критерий 2: heartbeat — чистый SSE-комментарий, а не именованное
     * событие: wire-форма {@code :heartbeat}, без {@code event:}/{@code data:}
     * строк (фронт с {@code addEventListener} по типу его поймать не может —
     * спецификация EventSource игнорирует комментарии сама, кода на фронте
     * не требуется).
     */
    @Test
    void heartbeat_wireForm_isCommentOnly() throws Exception {
        SseEventStreamService svc = service(60_000L);
        ProxyEmulatingEmitter emitter = new ProxyEmulatingEmitter();
        svc.registerClient(emitter, admin(), null, null, null);

        svc.sendHeartbeatToAll();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> !emitter.sent.isEmpty());

        String wire = builderText(emitter.sent.get(0));
        assertThat(wire)
            .as("heartbeat must serialize as a bare SSE comment")
            .isEqualTo(":heartbeat\n");
        assertThat(wire)
            .as("heartbeat must not look like a named event or data frame")
            .doesNotContain("event:")
            .doesNotContain("data:")
            .doesNotContain("id:");
    }

    /**
     * Критерий 2 (вторая половина): heartbeat не стреляет в хук доставки
     * доменных событий — наблюдаемость {@code onEventSent} остаётся чистой
     * (иначе счётчики/слушатели приняли бы keep-alive за событие).
     */
    @Test
    void heartbeat_neverNotifiesDeliveryHook() {
        SseEventStreamService svc = service(60_000L);
        ProxyEmulatingEmitter emitter = new ProxyEmulatingEmitter();
        svc.registerClient(emitter, admin(), null, null, null);

        List<Map<String, Object>> notified = new CopyOnWriteArrayList<>();
        svc.addEventListener((clientId, envelope) -> notified.add(envelope));

        svc.sendHeartbeatToAll();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> !emitter.sent.isEmpty());
        // Даём pump докачать (send асинхронен через send-lane): короткий
        // awaitility-полл вместо Thread.sleep (WO-OPS-14).
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .until(() -> notified.isEmpty() && emitter.sent.size() >= 1
                && pumpDrained(svc));

        assertThat(notified)
            .as("heartbeat must never fire the domain-event delivery hook")
            .isEmpty();
    }

    /**
     * Heartbeat при ПОЛНОЙ очереди пропускает тик, а не закрывает поток:
     * keep-alive не несёт данных, терять из-за него стрим (overflow-close)
     * нельзя — иначе давление событий превращалось бы в
     * heartbeat-убийства потока.
     */
    @Test
    void heartbeat_fullQueue_skipsWithoutClosing() throws Exception {
        SseEventStreamService svc = service(60_000L);
        ReflectionTestUtils.setField(svc, "perClientQueueEvents", 2);
        // Pump стоит: send висит на латче — очередь заполняется, но send не
        // отпускает (без таймаута: sendTimeoutMs=60s выше).
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
        String clientId = svc.registerClient(blocking, admin(), null, null, null);
        Object writer = clientObject(svc, clientId);

        // Два live-события заполняют очередь cap=2 (первое уходит в pump и
        // виснет на латче, второе ждёт).
        enqueueOnWriter(svc, writer, 1L, "user-task.created");
        enqueueOnWriter(svc, writer, 2L, "user-task.created");
        await().atMost(Duration.ofSeconds(5)).until(() -> queueSizeOfWriter(writer) >= 1);

        // Тик при полной очереди: пропуск, поток жив (map entry на месте).
        invokeWriterHeartbeat(writer);
        assertThat(clientObject(svc, clientId))
            .as("heartbeat on a full queue must skip, not overflow-close the stream")
            .isNotNull();

        release.countDown();
    }

    /**
     * Heartbeat до drain (BUFFERING-клиент) не оседает в пред-live очереди:
     * тик пропускается, drain потом не доставляет мусор.
     */
    @Test
    void heartbeat_bufferingClient_skipped() throws Exception {
        SseEventStreamService svc = service(60_000L);
        ProxyEmulatingEmitter emitter = new ProxyEmulatingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        Object writer = clientObject(svc, clientId);

        invokeWriterHeartbeat(writer);
        assertThat(queueSizeOfWriter(writer))
            .as("pre-live heartbeat must not stage in the buffering queue")
            .isEqualTo(0);

        svc.drainBufferedClient(clientId, 0L);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .until(() -> pumpDrained(svc));
        assertThat(emitter.sent)
            .as("no heartbeat must leak through the drain")
            .isEmpty();
    }

    // ---- helpers (тот же reflective-стиль, что SseRel47WriterProtocolTest) ----

    private static boolean isHeartbeat(SseEmitter.SseEventBuilder builder) {
        try {
            return ":heartbeat\n".equals(builderText(builder));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wire-текст builder'а: то же поле {@code sb}, которое Spring сериализует
     * в сокет (см. байткод {@code SseEventBuilderImpl}: {@code comment()}
     * пишет {@code ':' + text + '\n'} в {@code StringBuilder sb}).
     */
    private static String builderText(SseEmitter.SseEventBuilder builder) throws Exception {
        var f = builder.getClass().getDeclaredField("sb");
        f.setAccessible(true);
        return f.get(builder).toString();
    }

    private static Object clientObject(SseEventStreamService svc, String clientId) throws Exception {
        return sessionById(svc, clientId);
    }

    private static java.util.Collection<Object> sessionSnapshot(SseEventStreamService svc) throws Exception {
        // WO-AUDIT-9 шаг 3: реестр сессий переехал в SseSessionRegistry —
        // тот же snapshot-обход, что раньше по полю "clients" сервиса.
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

    private static void invokeWriterHeartbeat(Object writer) throws Exception {
        var m = writer.getClass().getDeclaredMethod("enqueueHeartbeat");
        m.setAccessible(true);
        m.invoke(writer);
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
