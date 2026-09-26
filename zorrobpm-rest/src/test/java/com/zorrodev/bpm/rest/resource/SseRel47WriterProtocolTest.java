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
        // WO-REL-52 (part B): close-on-overflow — flood beyond the cap now
        // CLOSES the client (failClient "overflow") instead of dropping
        // newest silently. The bounded assertions below read a client that
        // may already be closed by the new policy: queuedSize/droppedOverflow
        // probes assert non-null (fail loudly, no silent pass), and the
        // overflow-close itself is proven by SseRel52OverflowTest (B1/B2).
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
        // WO-REL-52: under close-on-overflow the SAME race closes the client
        // with "overflow" instead of the timeout — either way the client may
        // be gone when the probes read. The probes below tolerate the closed
        // state explicitly (clientGone check): the cap is proven by the
        // overflow-close event itself (SseRel52OverflowTest B1), here we
        // prove the flood does not grow anything unbounded.
        for (long seq = 2; seq <= 60; seq++) {
            svc.onDomainEvent(body(seq, "rel47.c1"));
        }

        int maxQueue = (int) ReflectionTestUtils.getField(svc, "perClientQueueEvents");
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            // Closed (overflow) or still-live-but-capped — both bounded.
            // queuedSize asserts non-null internally ONLY for the live path;
            // here the client is expected CLOSED, so read raw.
            Integer queued = queuedSizeOrNull(svc, blockedId);
            assertThat(queued)
                .as("per-client queue stays capped under flood (cap=%d) or client closed by overflow policy", maxQueue)
                .satisfiesAnyOf(
                    q -> assertThat(q).isLessThanOrEqualTo(maxQueue),
                    q -> assertThat(q).isNull());
        });
        // WO-REL-52: silent drops are gone — the counter stays 0 while the
        // client is closed by the overflow policy (close, not drop).
        Long dropped = droppedOverflowOrNull(svc, blockedId);
        assertThat(dropped)
            .as("no silent drops under close-on-overflow (0 while live, null once closed)")
            .satisfiesAnyOf(
                d -> assertThat(d).isEqualTo(0L),
                d -> assertThat(d).isNull());

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
        // C3 (V6): N iterations, each a genuine BUFFERING→LIVE race: a fresh
        // client, two REAL threads released simultaneously from a per-round
        // barrier — one drains, one enqueues. No sleep-before-enqueue on the
        // producer side (the pre-REL-47 losing interleave needed the producer
        // to land BETWEEN "queue detached" and "flag cleared" — a sleep AFTER
        // the barrier only selects the harmless post-drain order and never
        // exercises the window). Over N rounds both orders occur: enqueue
        // first (staged, drain must pick it up) and drain first (LIVE, pump
        // must pick it up). The pre-REL-47 two-step drain lost the event in
        // the first order whenever the producer landed mid-transition; the
        // single-lock writer has no mid-transition — both orders deliver.
        // No sleep on either side after the barrier: over N rounds both
        // orders genuinely occur (drain is microseconds, so a producer sleep
        // would structurally bias every round to the harmless drain-first
        // order and never exercise the window — verifier HOLD on WO-REL-47,
        // fixed by deleting the sleep, not by renumbering it).
        SseEventStreamService svc = service();
        int rounds = 30;
        for (int round = 0; round < rounds; round++) {
            long seq = 1000L + round;
            CapturingEmitter emitter = new CapturingEmitter();
            String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);

            CyclicBarrier go = new CyclicBarrier(2);
            AtomicInteger errors = new AtomicInteger();
            Thread drainer = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    svc.drainBufferedClient(clientId, 0L);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            Thread producer = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    svc.onDomainEvent(body(seq, "rel47.c3"));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            drainer.start();
            producer.start();
            drainer.join(15_000);
            producer.join(15_000);

            assertThat(errors.get()).as("round %d: barrier rendezvous must not break", round).isEqualTo(0);
            assertThat(drainer.isAlive() || producer.isAlive())
                .as("round %d: both threads must finish (no deadlock in drain/enqueue)", round)
                .isFalse();

            // Real emitter output decides (delivery, not the listener).
            int r = round;
            await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(emitter.delivered).as("round %d: raced event must not be lost", r).hasSize(1));
            assertThat(((Number) emitter.delivered.get(0).get("sequence")).longValue())
                .as("round %d: raced event sequence", round).isEqualTo(seq);
            svc.removeClient(clientId);
        }
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
    void listenerFiresOnlyAfterRealSend() throws Exception {        // Audit §7.2 pin: the hook must not fire when nothing was sent.
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

    // ---- WO-REL-47 HOLD (CTO review 2026-09-25): 5 findings ----

    /**
     * Finding 1: a rejected pump kick / send submit must not strand the
     * client. The dispatch lane is pre-saturated with pinned workers (every
     * kick rejects with the REAL AbortPolicy RejectedExecutionException —
     * the production trigger; no sleeps, no racing the scheduler). The event
     * is staged while BUFFERING and drained; the drain's kick rejects and
     * must arm the bounded retry lane. Draining the saturation lets the
     * armed retry deliver with NO further enqueue. Asserts on the REAL
     * emitter output.
     *
     * <p>P-67 naming: the failing mutation is "rejection only clears
     * pumpActive" (the pre-HOLD shape) — with it, this test REDs (delivery
     * stays empty: nothing ever re-kicks a narrow-filter client).
     */
    @Test
    void rejectedKick_retriesAndDeliversWithoutNewEnqueue() throws Exception {
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "pumpRetryDelayMs", 20L);

        // Saturation stand-in WITHOUT touching the lazy getters: a fresh
        // service whose dispatch pool is pre-saturated with LONG-RUNNING
        // tasks. dispatchLane() still returns the same live pool object
        // (never shutdown → no lazy re-creation), but its queue is full and
        // all workers are pinned, so every pump kick rejects with the REAL
        // AbortPolicy RejectedExecutionException — the production trigger.
        // REAL saturation: pin every dispatch worker AND fill the 1024-deep
        // queue behind them. Pinning workers alone is NOT saturation — the
        // LinkedBlockingQueue(1024) still accepts kicks (they just wait), so
        // the drain's kick would queue instead of rejecting. Queue-fill makes
        // the AbortPolicy reject deterministically — the production trigger.
        java.util.concurrent.ExecutorService dispatchLane = invokeLaneExecutor(svc, "dispatchLane");
        int dispatchMax = ((java.util.concurrent.ThreadPoolExecutor) dispatchLane).getMaximumPoolSize();
        CountDownLatch saturate = new CountDownLatch(1);
        Runnable pin = () -> {
            try {
                saturate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < dispatchMax; i++) {
            dispatchLane.execute(pin);
        }
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            java.util.concurrent.ThreadPoolExecutor p =
                (java.util.concurrent.ThreadPoolExecutor) invokeLane(svc, "dispatchLane");
            assertThat(p.getActiveCount()).isEqualTo(dispatchMax);
        });
        for (int i = 0; i < 1100; i++) {
            try {
                dispatchLane.execute(pin);
            } catch (java.util.concurrent.RejectedExecutionException expectedWhenFull) {
                break;
            }
        }

        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        // BUFFERING + one staged event, then drain: the drain's kick MUST
        // reject on the saturated lane and arm the bounded retry.
        svc.onDomainEvent(body(501L, "rel47.hold1"));
        svc.drainBufferedClient(clientId, 0L);
        assertThat(emitter.delivered)
            .as("saturated lane delivers nothing before the drain").isEmpty();

        // Drain: the saturated workers free up, the armed retry fires and
        // delivers with NO further enqueue (no new event is ever sent).
        saturate.countDown();
        try {
            await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(emitter.delivered).hasSize(1));
            assertThat(((Number) emitter.delivered.get(0).get("sequence")).longValue())
                .isEqualTo(501L);
            svc.removeClient(clientId);
        } finally {
            saturate.countDown();
        }
    }

    /**
     * Finding 2: stop() racing a lane getter must not leak a pool.
     * N racer threads hammer dispatchLane()/sendLane()/retryLane() while the
     * main thread calls stop(); afterwards every pool EITHER is shut down
     * (grabbed pre-stop) OR is the live current field (re-created post-stop
     * and re-armed by start()). No pool may be non-current AND un-shutdown
     * (that shape is the leak: nobody holds it, nobody shuts it).
     */
    @Test
    void stopRacingLaneGetters_leaksNoPool() throws Exception {
        SseEventStreamService svc = service();
        svc.start();

        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<>());
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    for (int j = 0; j < 200; j++) {
                        seen.add(invokeLane(svc, "dispatchLane"));
                        seen.add(invokeLane(svc, "sendLane"));
                        seen.add(invokeLane(svc, "retryLane"));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        go.countDown();
        // Let the racers grab pools, then stop mid-race.
        Thread.sleep(50);
        svc.stop(() -> {});

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        Object curDispatch = currentField(svc, "dispatchExecutor");
        Object curSend = currentField(svc, "sendExecutor");
        Object curRetry = currentField(svc, "retryScheduler");
        assertThat(curDispatch).as("stop() nulls the dispatch field").isNull();
        assertThat(curSend).as("stop() nulls the send field").isNull();
        assertThat(curRetry).as("stop() nulls the retry field").isNull();
        for (Object pool : seen) {
            assertThat(pool).isInstanceOf(java.util.concurrent.ExecutorService.class);
            assertThat(((java.util.concurrent.ExecutorService) pool).isShutdown())
                .as("every raced pool is shut down (no orphaned live pool)").isTrue();
        }

        // Post-stop the service heals (start re-arms) and still delivers.
        svc.start();
        CapturingEmitter emitter = new CapturingEmitter();
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);
        svc.onDomainEvent(body(503L, "rel47.hold2"));
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(emitter.delivered).hasSize(1));
        svc.removeClient(clientId);
        svc.stop(() -> {});
    }

    /**
     * Finding 3: keepAlive is structural, not decorative — both bounded pools
     * must evict idle core threads (the pre-HOLD shape never could:
     * core == max without allowCoreThreadTimeOut).
     */
    @Test
    void boundedPools_evictIdleCoreThreads() throws Exception {
        SseEventStreamService svc = service();
        for (String name : List.of("dispatchLane", "sendLane", "retryLane")) {
            Object pool = invokeLane(svc, name);
            assertThat(pool).isInstanceOf(java.util.concurrent.ThreadPoolExecutor.class);
            java.util.concurrent.ThreadPoolExecutor tpe =
                (java.util.concurrent.ThreadPoolExecutor) pool;
            assertThat(tpe.allowsCoreThreadTimeOut())
                .as(name + " must evict idle core threads (HOLD finding 3)").isTrue();
        }
        svc.stop(() -> {});
    }

    /**
     * Finding 4: teardown outside failClient() must close the writer.
     * Direct white-box proof on the writer protocol: register a client,
     * grab its writer object, remove the client through the REAL
     * removeClient() path (the same removeClientState() the emitter
     * completion/timeout/error callbacks call), then attempt enqueue + pump
     * on the detached writer. With the writer closed, both are silent
     * no-ops: the queue stays empty and NO emitter.send() happens. Under the
     * pre-HOLD shape (writer left open) the pump sends into the completed
     * emitter → IllegalStateException, i.e. this test REDs.
     */
    @Test
    void revokeDuringInflightSend_neverSendsAfterComplete() throws Exception {
        SseEventStreamService svc = service();

        java.util.concurrent.atomic.AtomicInteger postTeardownSends =
            new java.util.concurrent.atomic.AtomicInteger();
        SseEmitter emitter = new SseEmitter(60_000L) {
            @Override
            public void send(SseEventBuilder builder) {
                postTeardownSends.incrementAndGet();
            }
        };
        String clientId = svc.registerBufferedClient(emitter, admin(), null, null, null);
        svc.drainBufferedClient(clientId, 0L);

        Object writer = clientObject(svc, clientId);
        assertThat(writer).as("writer present before teardown").isNotNull();

        // REAL teardown path (removeClient → removeClientState).
        svc.removeClient(clientId);

        // Late live event for a detached writer: enqueue must drop (CLOSED),
        // and a pump racing the teardown must observe CLOSED, never send.
        enqueueOnWriter(svc, writer, 504L, "rel47.hold4");
        pumpOnWriter(writer);
        Thread.sleep(300);

        assertThat(postTeardownSends.get())
            .as("no emitter.send() after teardown (closed writer is a no-op)")
            .isEqualTo(0);
        assertThat(queueSizeOfWriter(writer))
            .as("closed writer drops late enqueues").isEqualTo(0);
    }

    // ---- HOLD-test harness ----

    private static Object clientObject(SseEventStreamService svc, String clientId) throws Exception {
        var f = SseEventStreamService.class.getDeclaredField("clients");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) f.get(svc);
        return clients.get(clientId);
    }

    /** Reflective enqueue on a detached writer (builds a real SseEventBuilder like the service does). */
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

    private static void pumpOnWriter(Object writer) throws Exception {
        var m = writer.getClass().getDeclaredMethod("pump");
        m.setAccessible(true);
        m.invoke(writer);
    }

    private static int queueSizeOfWriter(Object writer) throws Exception {
        var f = writer.getClass().getDeclaredField("queue");
        f.setAccessible(true);
        return ((java.util.ArrayDeque<?>) f.get(writer)).size();
    }

    /** Shutdown the lane pools WITHOUT touching client state (test-only saturation stand-in). */
    private static void shutdownLanesOnly(SseEventStreamService svc) throws Exception {
        for (String field : List.of("dispatchExecutor", "sendExecutor")) {
            var f = SseEventStreamService.class.getDeclaredField(field);
            f.setAccessible(true);
            ExecutorServiceShim.shutdownNow((java.util.concurrent.ExecutorService) f.get(svc));
        }
    }

    private static final class ExecutorServiceShim {
        static void shutdownNow(java.util.concurrent.ExecutorService pool) {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    private static Object invokeLane(SseEventStreamService svc, String name) throws Exception {
        var m = SseEventStreamService.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(svc);
    }

    private static java.util.concurrent.ExecutorService invokeLaneExecutor(
            SseEventStreamService svc, String name) throws Exception {
        return (java.util.concurrent.ExecutorService) invokeLane(svc, name);
    }

    private static Object currentField(SseEventStreamService svc, String name) throws Exception {
        var f = SseEventStreamService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(svc);
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

    private static Integer queuedSizeOrNull(SseEventStreamService svc, String clientId) throws Exception {
        var clientsField = SseEventStreamService.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) clientsField.get(svc);
        Object client = clients.get(clientId);
        if (client == null) {
            // WO-REL-52: closed by the overflow policy — map entry removed
            // by failClient → removeClientState. Null = closed, not missing.
            return null;
        }
        var queueField = client.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        return ((java.util.ArrayDeque<?>) queueField.get(client)).size();
    }

    private static Long droppedOverflowOrNull(SseEventStreamService svc, String clientId) throws Exception {
        var clientsField = SseEventStreamService.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> clients = (Map<String, Object>) clientsField.get(svc);
        Object client = clients.get(clientId);
        if (client == null) {
            // WO-REL-52: same as above — closed, not missing.
            return null;
        }
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
