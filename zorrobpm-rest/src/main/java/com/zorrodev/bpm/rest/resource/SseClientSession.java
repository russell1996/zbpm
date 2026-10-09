package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WO-AUDIT-9 (шаг 2): per-client single-writer state machine — бывший
 * внутренний {@code SseEventStreamService.SseClientInfo}, перенесённый
 * построчно, без смены семантики.
 *
 * <p>One value, one lock, three modes (WO-REL-47 N07+N08):
 * <ul>
 *   <li>BUFFERING — events accumulate in {@code queue} (catchup window,
 *       WO-REL-37); nothing is sent.</li>
 *   <li>LIVE — the pump sends strictly head-first, at most one send in
 *       flight per client (the pump submits the next send only after the
 *       previous settles — no arbitrary async tasks, no order inversion).</li>
 *   <li>CLOSED — terminal (overflow → close + reconnect catchup,
 *       timeout / failure / revoke / disconnect); enqueue/pump become
 *       no-ops.</li>
 * </ul>
 *
 * <p>The mode switch and the queue live under the SAME
 * {@code synchronized} protocol (never split flag+map like the pre-REL-47
 * {@code bufferingClients}+{@code bufferedEvents}, which could diverge
 * mid-drain and lose an event). Consequently a concurrent producer either
 * observes BUFFERING (its event lands in the queue BEFORE the drain
 * decision sees it) or LIVE (its event is queued for the pump) — the
 * lost-event interleave has no third outcome.
 *
 * <p>Identity (clientId … processDefinitionKeyFilter) lives in the linked
 * {@link SseClientDescriptor} — read by the authz paths; the writer state
 * (mode + queue + pump flag + counters) lives in private mutable fields
 * guarded by the instance monitor.
 *
 * <p>Back-calls into the owning service (lanes, config, terminal removal,
 * notify fan-out) go through {@link Host} — constructor-injected, no static
 * access, no outer-class capture.
 */
@Slf4j
public final class SseClientSession {

    /**
     * Back-calls the session needs from its owner (lanes + config +
     * terminal removal + notify fan-out). Implemented by
     * {@link SseEventStreamService} until the registry extraction (шаг 3).
     */
    public interface Host {
        ExecutorService dispatchLane();

        ExecutorService sendLane();

        ScheduledExecutorService retryLane();

        long pumpRetryDelayMs();

        long sendTimeoutMs();

        int perClientQueueEvents();

        /** Terminal removal (failClient path): registry state removal. */
        void terminalClose(SseClientSession session);

        /** Post-REAL-send fan-out to EventDispatchListeners. */
        void notifySent(SseClientSession session, Map<String, Object> envelope);
    }

    private final SseClientDescriptor descriptor;
    private final Host host;

    public SseClientSession(SseClientDescriptor descriptor, Host host) {
        this.descriptor = descriptor;
        this.host = host;
    }

    /** Max retry attempts per pump-retry arming (100ms × 50 = ~5s, one send-timeout window). */
    private static final int PUMP_RETRY_MAX_ATTEMPTS = 50;

    // Identity accessors (same names as the pre-AUDIT-9 record components).
    public String clientId() { return descriptor.clientId(); }
    public SseEmitter emitter() { return descriptor.emitter(); }
    public Principal principal() { return descriptor.principal(); }
    public int tokenVersion() { return descriptor.tokenVersion(); }
    public Collection<UUID> allowedPdIds() { return descriptor.allowedPdIds(); }
    public String typeFilter() { return descriptor.typeFilter(); }
    public String processInstanceIdFilter() { return descriptor.processInstanceIdFilter(); }
    public String processDefinitionKeyFilter() { return descriptor.processDefinitionKeyFilter(); }

    /** The linked identity descriptor. */
    public SseClientDescriptor descriptor() { return descriptor; }

    private enum Mode { BUFFERING, LIVE, CLOSED }

    private Mode mode = Mode.BUFFERING;
    /**
     * WO-REL-47: the queued unit is builder + cursor + envelope TOGETHER.
     * The envelope is already retained by the builder via
     * {@code .data(envelope)} (wire payload) — the record only adds the
     * cursor (drain dedup without re-parsing) and a direct envelope
     * handle (post-send notify without re-extracting). No double
     * retention worth mentioning: two references to the same map.
     *
     * <p>WO-REL-57: {@code heartbeat} marks a keep-alive comment (no
     * cursor, no envelope — {@code Long.MIN_VALUE}/{@code null}): the
     * drain drops it unconditionally (a pre-LIVE tick carries nothing
     * worth delivering) and the post-send hook skips it (delivery
     * observability is for domain events, not keep-alives).
     *
     * <p>WO-REL-70: {@code hello} marks the immediate post-registration
     * greeting (no cursor, no envelope — same {@code Long.MIN_VALUE}/
     * {@code null} shape). Unlike a heartbeat it is KEPT by the drain
     * (head-first, before any staged live events): its whole purpose is
     * to be the first flushed bytes. The post-send hook skips it for the
     * same reason as a heartbeat (no domain delivery to observe).
     */
    private record QueuedSend(SseEmitter.SseEventBuilder event, long cursor,
            Map<String, Object> envelope, boolean heartbeat, boolean hello) {}
    private final java.util.ArrayDeque<QueuedSend> queue = new java.util.ArrayDeque<>();
    /**
     * True while a send is in flight OR a pump run is scheduled: exactly
     * one pump continuation is ever outstanding per client (single
     * writer), so sends never overlap and never reorder.
     */
    private boolean pumpActive;
    /** WO-REL-47: overflow drops counted here (drop-newest, survivors ordered).
     * WO-REL-52: поле оставлено намеренно (не удалено): REL-47-тест
     * {@code slowClient_backpressureIsBounded} читает его white-box пробой
     * как границу очереди; после перехода на close-on-overflow оно всегда
     * 0 — это тоже утверждение (счётчик тихих потерь молчит, потому что
     * тихих потерь больше нет). Удаление поля сломало бы пробой без пользы.
     */
    private long droppedOverflow;

    /** BUFFERING→BUFFERING idempotent (explicit protocol step, see registerBufferedClient). */
    synchronized void setBuffering() {
        // Fresh clients are born BUFFERING; only BUFFERING accepts this.
    }

    /**
     * WO-REL-47: live enqueue — single protocol step. BUFFERING: stage for
     * the drain decision. LIVE: queue for the pump + kick. CLOSED: drop.
     * Overflow (beyond per-client cap): CLOSE the client
     * (WO-REL-52 part B — see below), do not drop silently.
     *
     * <p>WO-REL-52 (NEW-04, part B): close-on-overflow, not silent drop.
     * The client is slower than the event flow (but inside the 5s send
     * timeout — otherwise the timeout path already closed it): events
     * 1…1000 arrive, 1001…N would be silently skipped, then N+1… would
     * resume — the client's Last-Event-ID jumps the hole and catchup on
     * the next reconnect starts AFTER it (the event is lost for this
     * client forever). Closing the stream instead makes the browser
     * reconnect with the last REALLY delivered id and heal the hole
     * through the regular catchup path (WO-REL-37), which already
     * exists. The close is reasoned ("overflow") and counted.
     */
    void enqueueLive(SseEmitter.SseEventBuilder event, Map<String, Object> envelope, long cursor) {
        boolean kick = false;
        boolean overflowed = false;
        synchronized (this) {
            if (mode == Mode.CLOSED) {
                return;
            }
            if (queue.size() >= host.perClientQueueEvents()) {
                // WO-REL-52: terminal for this writer — but the close
                // itself (failClient → terminal removal + emitter) runs
                // OUTSIDE the client lock (see below): the terminal path takes
                // other locks/orderings and must never run under it.
                mode = Mode.CLOSED;
                queue.clear();
                overflowed = true;
            } else {
                queue.addLast(new QueuedSend(event, cursor, envelope, false, false));
                if (mode == Mode.LIVE && !pumpActive) {
                    pumpActive = true;
                    kick = true;
                }
            }
        }
        if (overflowed) {
            log.warn("SSE client {} queue full ({} events) — closing (overflow), reconnect heals via catchup",
                descriptor.clientId(), host.perClientQueueEvents());
            failClient("overflow");
            return;
        }
        if (kick) {
            kickPump();
        }
    }

    /**
     * WO-REL-37 (F14) + WO-REL-47: BUFFERING→LIVE under the client lock.
     * The dedup decision (cursor <= boundary → drop) runs on the drained
     * snapshot in queue order (cursor 100 before 101 — criterion 4), then
     * the survivors are re-queued head-first and the pump is kicked if
     * anything remains. After this call returns, no producer can observe
     * BUFFERING anymore: anything enqueued later takes the LIVE branch of
     * {@link #enqueueLive} — the lost-event window is closed.
     */
    void drainToLive(long catchupBoundary) {
        boolean kick = false;
        synchronized (this) {
            if (mode != Mode.BUFFERING) {
                return;
            }
            mode = Mode.LIVE;
            if (!queue.isEmpty()) {
                java.util.ArrayDeque<QueuedSend> survivors = new java.util.ArrayDeque<>();
                for (QueuedSend queued : queue) {
                    // WO-REL-70: hello переживает drain (это его смысл —
                    // быть первыми сброшенными байтами); head-first порядок
                    // survivors его сохраняет впереди staged live-событий.
                    if (queued.hello) {
                        survivors.addLast(queued);
                        continue;
                    }
                    // WO-REL-57: heartbeat ticks queued pre-LIVE carry no
                    // cursor and nothing worth delivering — drop, the next
                    // tick re-arms on the live writer.
                    if (queued.heartbeat) {
                        continue;
                    }
                    // WO-REL-38: дедуп по позиции курсора (feedPosition;
                    // fallback — sequence, см. cursorOf в сервисе).
                    if (queued.cursor <= catchupBoundary) {
                        continue;
                    }
                    survivors.addLast(queued);
                }
                queue.clear();
                queue.addAll(survivors);
            }
            if (!queue.isEmpty() && !pumpActive) {
                pumpActive = true;
                kick = true;
            }
        }
        if (kick) {
            kickPump();
        }
    }

    /**
     * WO-REL-55 (NEW2-09, часть B): gap-close видит только LIVE-писателей.
     * BUFFERING-клиенты (поток не стартовал) лечатся drain-пересечением,
     * CLOSED — уже сняты.
     */
    synchronized boolean isLive() {
        return mode == Mode.LIVE;
    }

    /** Close the writer: terminal, idempotent; the pump drains to no-op. */
    synchronized void closeWriter() {
        mode = Mode.CLOSED;
        queue.clear();
    }

    /**
     * WO-REL-57: queue one heartbeat comment for the pump. LIVE only
     * (BUFFERING ticks would sit in the pre-drain queue and be dropped
     * by the drain anyway — skip them early). A FULL queue skips the
     * tick for this client WITHOUT closing: the heartbeat carries no
     * data worth losing the stream over, and closing here would turn
     * event pressure into heartbeat-driven overflow kills. CLOSED drops.
     */
    void enqueueHeartbeat() {
        boolean kick = false;
        synchronized (this) {
            if (mode != Mode.LIVE) {
                return;
            }
            if (queue.size() >= host.perClientQueueEvents()) {
                return;
            }
            queue.addLast(new QueuedSend(SseWireProtocol.heartbeatEvent(),
                Long.MIN_VALUE, null, true, false));
            if (!pumpActive) {
                pumpActive = true;
                kick = true;
            }
        }
        if (kick) {
            kickPump();
        }
    }

    /**
     * WO-REL-70: queue the immediate post-registration greeting through
     * the client's own writer (never a parallel direct
     * {@code emitter.send} — the REL-47 single-writer protocol stays the
     * only sender). BUFFERING: stage it (the drain keeps it head-first,
     * kicks the pump — first flushed bytes within milliseconds of the
     * controller returning). LIVE: queue + kick (defensive — the
     * controller calls this pre-drain, so this branch is not the live
     * path). CLOSED: drop. A FULL queue drops the hello WITHOUT closing:
     * the greeting carries no data worth losing the stream over (same
     * rationale as {@link #enqueueHeartbeat}).
     */
    void enqueueHello() {
        boolean kick = false;
        synchronized (this) {
            if (mode == Mode.CLOSED) {
                return;
            }
            if (queue.size() >= host.perClientQueueEvents()) {
                return;
            }
            queue.addLast(new QueuedSend(SseWireProtocol.helloEvent(),
                Long.MIN_VALUE, null, false, true));
            if (mode == Mode.LIVE && !pumpActive) {
                pumpActive = true;
                kick = true;
            }
        }
        if (kick) {
            kickPump();
        }
    }

    /**
     * Single-writer pump: poll head, send it with timeout, repeat while
     * the queue is non-empty — strictly head-first (order = enqueue
     * order). At most one send in flight per client: the continuation
     * (next poll) is scheduled only after the current send settles, via
     * the orTimeout callback — no pooled thread ever waits on a send.
     */
    void pump() {
        QueuedSend head;
        synchronized (this) {
            if (mode == Mode.CLOSED) {
                pumpActive = false;
                return;
            }
            head = queue.pollFirst();
            if (head == null) {
                pumpActive = false;
                return;
            }
        }
        sendOne(head);
    }

    /**
     * One send on the send lane with a non-blocking timeout: the caller
     * (dispatch lane) returns immediately after submit; settle/timeout
     * handling runs as an orTimeout callback. Slow clients pin at most
     * one send-lane thread each (bounded pool caps the total); the queue
     * behind them stays bounded by the per-client cap.
     */
    private void sendOne(QueuedSend queued) {
        CompletableFuture<Void> send;
        // WO-URGENT-1 (NEW2-03): the CLOSED-drop below returns WITHOUT
        // sending, but the continuation used to treat "no exception" as
        // "delivered" and called notifySent anyway — a closed writer
        // reported sends that never happened (phantom delivery,
        // notified 1 != delivered 0). The flag carries the fact of a
        // REAL send across the async boundary; notifySent fires only
        // when an actual emitter.send() happened.
        java.util.concurrent.atomic.AtomicBoolean actuallySent =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            send = CompletableFuture.runAsync(() -> {
                try {
                    // WO-REL-47 HOLD finding 4: the writer may have closed
                    // while this send was queued (revoke / disconnect /
                    // stop) — the emitter below may already be completed.
                    // Never send into a closed writer: drop silently
                    // instead of emitting into IllegalStateException and a
                    // misleading "send error" log line.
                    synchronized (SseClientSession.this) {
                        if (mode == Mode.CLOSED) {
                            return;
                        }
                    }
                    descriptor.emitter().send(queued.event);
                    actuallySent.set(true);
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, host.sendLane());
        } catch (java.util.concurrent.RejectedExecutionException re) {
            // WO-REL-47 HOLD finding 1: send lane saturated (all threads
            // pinned by slow clients). Re-queue at HEAD (order preserved)
            // and re-arm the pump through the bounded retry lane — the
            // pre-HOLD shape only cleared pumpActive ("reactivates on the
            // next kick"), which stranded narrow-filter clients whose
            // next matching event might never come.
            synchronized (this) {
                if (mode != Mode.CLOSED) {
                    queue.addFirst(queued);
                }
                pumpActive = false;
            }
            schedulePumpRetry("send-lane saturated");
            return;
        }
        send.orTimeout(host.sendTimeoutMs(), TimeUnit.MILLISECONDS).whenCompleteAsync((v, ex) -> {
            if (ex == null) {
                // WO-URGENT-1 (NEW2-03): report a delivery ONLY when the
                // send above really ran. A CLOSED-drop (flag unset) still
                // pumps so pumpActive resets through the CLOSED no-op —
                // but never notifies.
                // WO-REL-57: heartbeats never notify either (keep-alive,
                // not delivery — the listener hook is for domain events).
                // WO-REL-70: hello never notifies either (greeting, not
                // delivery — same reason).
                if (actuallySent.get() && !queued.heartbeat && !queued.hello) {
                    host.notifySent(SseClientSession.this, queued.envelope);
                }
                pump();
                return;
            }
            Throwable cause = ex instanceof java.util.concurrent.CompletionException ce
                ? ce.getCause() : ex;
            if (cause instanceof TimeoutException
                    || ex instanceof java.util.concurrent.TimeoutException) {
                // WO-PERF-6: timeout — lagging client is dropped (its
                // cursor catchup on reconnect heals the gap, WO-REL-47
                // criterion 3 path), never blocking the rest.
                // WO-PERF-6: cancel(true) does NOT interrupt a blocking
                // network write (Java IO without InterruptibleChannel) —
                // the thread frees only when emitter.complete()/
                // IOException fires, non-deterministically. We at least
                // isolate the block to ONE send-lane slot per client.
                log.warn("Slow SSE client {} timed out ({}ms), dropping", descriptor.clientId(), host.sendTimeoutMs());
                send.cancel(true);
                failClient("send timeout");
            } else if (isSendIoFailure(cause)) {
                log.warn("Failed to send event to client {}: {}", descriptor.clientId(), cause.getMessage());
                failClient("send failure");
            } else if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                failClient("send interrupted");
            } else {
                log.error("Error sending event to client {}", descriptor.clientId(), cause != null ? cause : ex);
                failClient("send error");
            }
        }, host.dispatchLane());
    }

    /**
     * WO-REL-47 HOLD finding 1: bounded re-arm of the pump after a lane
     * rejection. Schedules up to {@value #PUMP_RETRY_MAX_ATTEMPTS}
     * attempts at the host's pump-retry delay intervals; each attempt takes
     * the pump slot (pumpActive) under the client lock and runs
     * {@link #pump} on the dispatch lane. Attempts stop early when the
     * queue drains, the writer closes, or the budget runs out — whichever
     * comes first. A later enqueue/drain kick re-arms independently, so a
     * retry budget exhausted under SUSTAINED saturation resumes as soon as
     * new work arrives or the lanes free up.
     *
     * @param reason log context (which lane rejected)
     */
    private void schedulePumpRetry(String reason) {
        ScheduledExecutorService lane;
        try {
            lane = host.retryLane();
        } catch (java.util.concurrent.RejectedExecutionException re) {
            log.warn("SSE client {} pump stalled ({}; retry lane saturated)", descriptor.clientId(), reason);
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger attemptsLeft =
            new java.util.concurrent.atomic.AtomicInteger(PUMP_RETRY_MAX_ATTEMPTS);
        final java.util.concurrent.ScheduledFuture<?>[] holder =
            new java.util.concurrent.ScheduledFuture<?>[1];
        long retryDelayMs = host.pumpRetryDelayMs();
        holder[0] = lane.scheduleWithFixedDelay(() -> {
            // WO-REL-47 HOLD finding 4: never pump a closed writer —
            // the retry is a no-op (and self-cancels) once CLOSED.
            boolean run = false;
            synchronized (SseClientSession.this) {
                if (mode == Mode.CLOSED) {
                    run = false;
                } else if (!queue.isEmpty() && !pumpActive) {
                    pumpActive = true;
                    run = true;
                } else if (queue.isEmpty()) {
                    run = false;
                } else {
                    // Pump already active (a kick got through meanwhile):
                    // this arming is redundant — cancel it.
                    run = false;
                }
            }
            if (run) {
                try {
                    host.dispatchLane().execute(this::pump);
                } catch (java.util.concurrent.RejectedExecutionException re) {
                    synchronized (SseClientSession.this) {
                        pumpActive = false;
                    }
                    // Stay armed: the next tick retries again (budget
                    // still applies below).
                }
                if (attemptsLeft.decrementAndGet() <= 0) {
                    holder[0].cancel(false);
                    log.warn("SSE client {} pump retry budget exhausted ({}); "
                        + "next enqueue/drain kick re-arms", descriptor.clientId(), reason);
                }
                return;
            }
            // Terminal states for this arming: closed, drained, or
            // superseded by a live pump — cancel, do not spin.
            if (mode == Mode.CLOSED || queue.isEmpty() || attemptsLeft.get() <= 0) {
                holder[0].cancel(false);
            } else if (pumpActive) {
                holder[0].cancel(false);
            }
        }, retryDelayMs, retryDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * WO-REL-47 HOLD finding 4: the writer also closes HERE (mode=CLOSED,
     * queue dropped): the terminal removal already closes it — this is the
     * second, belt-and-braces close for the one path that never goes
     * through the registry removal first (failClient is the terminal path;
     * the emitter is completed below and no pump/send may touch it after).
     */
    private void failClient(String reason) {
        closeWriter();
        // WO-SEC-67 red-team #3: full state removal (per-subject slot),
        // not a bare map drop — the slot would leak.
        host.terminalClose(this);
        try {
            descriptor.emitter().complete();
        } catch (Exception ignore) {
        }
        log.info("SSE client {} closed ({})", descriptor.clientId(), reason);
    }

    /** Kick the pump on the dispatch lane; a reject re-arms via the bounded retry lane. */
    private void kickPump() {
        try {
            host.dispatchLane().execute(this::pump);
        } catch (java.util.concurrent.RejectedExecutionException re) {
            // WO-REL-47 HOLD finding 1: dispatch lane saturated. The
            // pre-HOLD shape only cleared pumpActive ("self-heals via the
            // next kick") — a client with a narrow filter might never get
            // that kick. Bounded retry lane re-arms the pump instead.
            synchronized (this) {
                pumpActive = false;
            }
            schedulePumpRetry("dispatch-lane saturated");
        }
    }

    /**
     * WO-REL-47: unwraps CompletionException layers to the send's real
     * cause (the pre-REL-47 code distinguished the IOException cause two
     * levels down for the log line — same classification, kept).
     */
    private static boolean isSendIoFailure(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
