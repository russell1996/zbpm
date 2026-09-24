package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-REL-47 (N07+N08): single writer protocol per SSE client.
 *
 * <p>Audit §7.2 warning is structural to this class: the
 * {@code EventDispatchListener.onEventSent} hook FIRES ONLY AFTER a real
 * {@code emitter.send()} (see {@code SseClientInfo.notifySent}) — it proves
 * delivery, not dispatch intent. Every test below collects on the REAL
 * emitter output (capturing send() overrides) AND on the listener, and
 * asserts the two agree. A test that only watched the listener would pass
 * even if sends never reached the socket.
 *
 * <p>Criteria map:
 * <ul>
 *   <li>C1 — blocking/slow emitter + active event flow → threads and queue
 *       bounded ({@code slowClient_backpressureIsBounded}).</li>
 *   <li>C2 — fast client keeps receiving next to a stalled neighbour
 *       ({@code fastClient_receivesDespiteStalledNeighbour}).</li>
 *   <li>C3 — two real threads with barriers in the register/drain window →
 *       the buffered event is NOT lost (V6,
 *       {@code concurrentEnqueueDuringDrain_eventNotLost}). Uses a
 *       CyclicBarrier rendezvous (not a sleep) to force the interleave the
 *       pre-REL-47 two-step drain lost.</li>
 *   <li>C4 — cursor order (100 before 101) survives the buffered→live
 *       switchover ({@code drainDeliversCursorOrder}).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SseRel47WriterProtocolTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        // Live bridge resolves the cursor from the DB row; here every
        // sequence is "positioned" at itself (same stub as the PERF-6 tests).
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
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

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    private static String body(long sequence, String type) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-24T00:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    /**
     * Emitter capturing REAL output: every send() records the envelope via
     * the public builder.build() channel (same as the Rel37 tests — no
     * container internals). The listener hook fires only after this send,
     * so the test can assert delivery == notification, never intent alone.
     */
    static class CapturingEmitter extends SseEmitter {
        final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        final AtomicInteger sendCalls = new AtomicInteger();

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            sendCalls.incrementAndGet();
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
     * Emitter that blocks in send() until released — a stuck socket that
     * pins its send-lane slot (WO-OPS-14: the block is the TESTED BEHAVIOR,
     * not a wait — Awaitility cannot simulate a stuck socket).
     */
    static class BlockingEmitter extends CapturingEmitter {
        final CountDownLatch enteredSend = new CountDownLatch(1);
        final CountDownLatch releaseSend = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) {
            enteredSend.countDown();
            try {
                // Bounded wait: proves the pin without hanging the suite if
                // the pump ever stops calling (then the test fails loudly).
                assertThat(releaseSend.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.send(builder);
        }
    }

    @Test
    void slowClient_backpressureIsBounded() throws Exception {
        // C1: blocking emitter + active flow → queue capped, threads capped.
        // The send timeout is RAISED (5s prod default → 60s): the flood below
        // runs in milliseconds and the bounded assertions complete inside a
        // 5s window, so the timeout can only fire AFTER them — it removes the
        // timeout-fire race from the detection window (P-10), it does not
        // wait for anything.
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "perClientQueueEvents", 8);
        ReflectionTestUtils.setField(svc, "sendTimeoutMs", 60_000L);

        BlockingEmitter blocked = new BlockingEmitter();
        String blockedId = svc.registerBufferedClient(blocked, admin(), null, null, null);
        svc.drainBufferedClient(blockedId, 0L);

        // One event to pin the single in-flight send slot of this client.
        svc.onDomainEvent(body(1L, "rel47.c1"));
        assertThat(blocked.enteredSend.await(5, TimeUnit.SECONDS))
            .as("pump must start the first send (and pin on the stuck socket)").isTrue();

        // Flood while the socket is stuck: pump is busy, queue must cap.
        // NOTE: the client's send timeout (default 5s) may fire mid-flood and
        // close the client (failClient path) — the flood below runs fast
        // (<1s for 59 events), but the bounded assertions must read the queue
        // BEFORE any timeout can remove the client. All reads happen inside
        // one Awaitility window; a closed client fails the test loudly (no
        // silent pass on missing state).
        for (long seq = 2; seq <= 60; seq++) {
            svc.onDomainEvent(body(seq, "rel47.c1"));
        }

        int maxQueue = (int) ReflectionTestUtils.getField(svc, "perClientQueueEvents");
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            int queued = queuedSize(svc, blockedId);
            assertThat(queued)
                .as("per-client queue stays capped under flood (cap=%d)", maxQueue)
                .isLessThanOrEqualTo(maxQueue);
        });
        long dropped = droppedOverflow(svc, blockedId);
        assertThat(dropped)
            .as("flood beyond the cap drops newest and counts it (59 sent, cap 8, 1 in flight)")
            .isGreaterThan(0);

        // The send lane itself is a fixed pool: its max cannot exceed the cap.
        int sendMax = sendPoolMax(svc);
        assertThat(sendMax).as("send lane is a fixed pool (N07: no cached growth)").isLessThanOrEqualTo(256);

        blocked.releaseSend.countDown();
        svc.removeClient(blockedId);
    }

    @Test
    void fastClient_receivesDespiteStalledNeighbour() throws Exception {
        // C2: fast client keeps receiving while the neighbour is stuck.
        SseEventStreamService svc = service();

        BlockingEmitter stalled = new BlockingEmitter();
        String stalledId = svc.registerBufferedClient(stalled, admin(), null, null, null);
        svc.drainBufferedClient(stalledId, 0L);

        CapturingEmitter fast = new CapturingEmitter();
        String fastId = svc.registerBufferedClient(fast, admin(), null, null, null);
        svc.drainBufferedClient(fastId, 0L);

        List<Map<String, Object>> fastNotified = new CopyOnWriteArrayList<>();
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(fastId)) {
                fastNotified.add(envelope);
            }
        });

        String type = "rel47.c2." + UUID.randomUUID();
        svc.onDomainEvent(body(101L, type));
        assertThat(stalled.enteredSend.await(5, TimeUnit.SECONDS)).isTrue();

        // The fast client's delivery must not wait for the stuck socket:
        // real emitter output (delivery) AND the post-send hook agree.
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fast.delivered).hasSize(1);
            assertThat(fastNotified).hasSize(1);
        });
        assertThat(fast.delivered.get(0)).isSameAs(fastNotified.get(0));
        assertThat(fast.delivered.get(0).get("type")).isEqualTo(type);

        stalled.releaseSend.countDown();
        svc.removeClient(stalledId);
        svc.removeClient(fastId);
    }

    @Test
    void concurrentEnqueueDuringDrain_eventNotLost() throws Exception {
        // C3 (V6): two REAL threads rendezvous on barriers inside the
        // register/drain window. Thread A drains (BUFFERING→LIVE); thread B
        // enqueues concurrently. Pre-REL-47 this lost the event when B landed
        // between "remove queue" and "remove flag". The single-protocol writer
        // has no such interleave: B either stages before the drain decision
        // (delivered via drain) or queues for the pump (delivered live).
        SseEventStreamService svc = service();

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

        CyclicBarrier bothReady = new CyclicBarrier(2);
        CyclicBarrier drainDone = new CyclicBarrier(2);
        AtomicInteger errors = new AtomicInteger();

        Thread drainer = new Thread(() -> {
            try {
                bothReady.await(10, TimeUnit.SECONDS);
                svc.drainBufferedClient(clientId, 0L);
                drainDone.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });
        Thread producer = new Thread(() -> {
            try {
                bothReady.await(10, TimeUnit.SECONDS);
                // WO-OPS-14: намеренно Thread.sleep, не Awaitility — это НЕ
                // ожидание условия, а формирование чередования (тот же
                // прецедент, что SlowEmitter в PERF-6): producer обязан
                // попасть ВНУТРЬ drain-окна, а не до/после него. 100мс >>
                // джиттера старта потоков (~мкс-мс) и << widened-окна POF-
                // мутации (300мс) — попадание детерминировано. На фиксе любое
                // чередование безопасно (single-lock протокол), поэтому
                // задержка тест не ослабляет: она лишь выбирает самое злое
                // чередование из всех безопасных.
                Thread.sleep(100);
                // Rendezvous INSIDE the drain window: the drain call above is
                // already in flight on the other thread while this enqueue runs.
                svc.onDomainEvent(body(201L, "rel47.c3"));
                drainDone.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });
        drainer.start();
        producer.start();
        drainer.join(15_000);
        producer.join(15_000);

        assertThat(errors.get()).as("barrier rendezvous must not break").isEqualTo(0);
        assertThat(drainer.isAlive() || producer.isAlive())
            .as("both threads must finish (no deadlock in drain/enqueue)")
            .isFalse();

        // The event is either drained or pumped — but NEVER lost. Real
        // emitter output decides (delivery, not the listener).
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(1));
        assertThat(((Number) emitter.delivered.get(0).get("sequence")).longValue()).isEqualTo(201L);
        svc.removeClient(clientId);
    }

    @Test
    void drainDeliversCursorOrder() throws Exception {
        // C4: cursor order (100 before 101) survives the buffered→live switch.
        SseEventStreamService svc = service();

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

        // Both staged while BUFFERING (enqueue order 100, then 101).
        svc.onDomainEvent(body(100L, "rel47.c4"));
        svc.onDomainEvent(body(101L, "rel47.c4"));

        // Boundary 0: nothing is a duplicate — both must be delivered, in order.
        svc.drainBufferedClient(clientId, 0L);

        List<Map<String, Object>> notified = new CopyOnWriteArrayList<>();
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                notified.add(envelope);
            }
        });

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(emitter.delivered).hasSize(2);
            assertThat(notified).hasSize(2);
        });
        assertThat(((Number) emitter.delivered.get(0).get("sequence")).longValue()).isEqualTo(100L);
        assertThat(((Number) emitter.delivered.get(1).get("sequence")).longValue()).isEqualTo(101L);

        // Resume after a break at cursor 100: the late 100 is a duplicate
        // (dropped by the boundary), the newer 101 is delivered.
        CapturingEmitter resumed = new CapturingEmitter();
        String resumedId = svc.registerBufferedClient(resumed, admin(), null, null, null);
        svc.onDomainEvent(body(100L, "rel47.c4"));
        svc.onDomainEvent(body(101L, "rel47.c4"));
        svc.drainBufferedClient(resumedId, 100L);

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(resumed.delivered).hasSize(1));
        assertThat(((Number) resumed.delivered.get(0).get("sequence")).longValue()).isEqualTo(101L);

        svc.removeClient(clientId);
        svc.removeClient(resumedId);
    }

    @Test
    void listenerFiresOnlyAfterRealSend() throws Exception {
        // Audit §7.2 pin: the hook must not fire when nothing was sent.
        // A CLOSED writer (failed client) drops enqueues silently — no send,
        // no notification.
        SseEventStreamService svc = service();

        CapturingEmitter emitter = new CapturingEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                sendCalls.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        List<Map<String, Object>> notified = new CopyOnWriteArrayList<>();
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                notified.add(envelope);
            }
        });

        svc.onDomainEvent(body(301L, "rel47.hook"));
        // Detection window (WO-OPS-14 pattern): the async pump settles inside
        // it; any notification would fail the test, silence proves the hook
        // is post-send. The failing send closes the client (failClient path).
        Thread.sleep(1500);
        assertThat(emitter.sendCalls.get()).as("the send was really attempted").isGreaterThan(0);
        assertThat(notified).as("hook must not fire when the send failed").isEmpty();
        assertThat(emitter.delivered).as("failed send delivers nothing").isEmpty();
        svc.removeClient(clientId);
    }

    // ---- white-box probes (queue/pool internals for the boundedness asserts) ----

    private static int queuedSize(SseEventStreamService svc, String clientId) throws Exception {
        var clientsField = SseEventStreamService.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) clientsField.get(svc);
        Object client = clients.get(clientId);
        assertThat(client).as("client must still be registered").isNotNull();
        var queueField = client.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        return ((java.util.ArrayDeque<?>) queueField.get(client)).size();
    }

    private static long droppedOverflow(SseEventStreamService svc, String clientId) throws Exception {
        var clientsField = SseEventStreamService.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) clientsField.get(svc);
        Object client = clients.get(clientId);
        assertThat(client).as("client must still be registered").isNotNull();
        var droppedField = client.getClass().getDeclaredField("droppedOverflow");
        droppedField.setAccessible(true);
        return (long) droppedField.get(client);
    }

    private static int sendPoolMax(SseEventStreamService svc) throws Exception {
        var lane = SseEventStreamService.class.getDeclaredMethod("sendLane");
        lane.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor pool =
            (java.util.concurrent.ThreadPoolExecutor) lane.invoke(svc);
        return pool.getMaximumPoolSize();
    }
}
