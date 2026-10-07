package com.zorrodev.bpm.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WO-REL-16: declares the {@code zorrobpm.jobs.*} queues the engine announces, so a job type's
 * queue exists from deployment/startup rather than only once the first message is sent to it.
 *
 * <p>Declaring a durable queue with identical arguments is idempotent on the broker, so re-running
 * this is safe; {@link #declared} exists purely to avoid a needless broker round-trip per message
 * on the send path. It is a performance cache, NOT state of record — losing it on restart just
 * means we declare again, which is exactly what we do at startup anyway. (Contrast with engine
 * execution state, which must live in the database.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobQueueDeclarer {

    /** Queue name prefix for job-worker queues: {@code zorrobpm.jobs.<jobType>}. */
    public static final String JOB_QUEUE_PREFIX = "zorrobpm.jobs.";

    /**
     * WO-REL-45: one shared dead-letter exchange for all per-job-type queues
     * (same shape as {@code COMPLETE_DLX} for the completion path — one DLX,
     * per-queue routing keys). A poison message a worker keeps NACKing is parked
     * by the broker instead of redelivered forever or dropped on the floor.
     */
    public static final String JOBS_DLX = "zorrobpm.jobs.dlx";

    /** DLQ name for one job type — per-type (not one shared DLQ), so an operator
     *  sees WHICH job type is poisoned without opening the message. */
    public static String dlqNameFor(String jobType) {
        return JOB_QUEUE_PREFIX + jobType + ".dlq";
    }

    /** Micrometer gauge: how many job-type queues are currently pinned in legacy
     *  (pre-REL-45, no-DLX) mode — operator visibility that this job type runs
     *  WITHOUT a working DLQ. Zero when everything is migrated. */
    public static final String LEGACY_QUEUE_METRIC = "zbpm.jobs.legacy_queue";

    private final AmqpAdmin amqpAdmin;

    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    /**
     * WO-REL-51: queue names whose broker definition predates REL-45 (declared
     * WITHOUT the DLX arguments). The broker answers every redeclare-with-args
     * with {@code 406 PRECONDITION_FAILED} and closes the channel — retrying
     * that on every message is a permanent 4-RPC + error-stack storm that can
     * never succeed (only an operator migration changes the broker-side
     * definition). Terminal for this JVM: the name stays in {@link #declared}
     * (no more attempts), the fact is recorded here + warn-logged ONCE.
     * Cleared only by restart (fresh instance) — the migration runbook ends
     * with an app restart for exactly this reason.
     */
    private final Set<String> legacyDeclared = ConcurrentHashMap.newKeySet();

    /**
     * WO-REL-66: per-queue last attempt of {@link #redeclareForSend}, monotonic
     * millis. The returns-callback runs on the connection thread per returned
     * message — without a window a lost queue would redeclare on EVERY redelivered
     * send (declare storm on the broker). One attempt per queue per window; the
     * retry itself comes from the outbox poller tick (at-least-once resend), not
     * from repeating the declare here.
     */
    static final long REDECLARE_DEBOUNCE_MS = 10_000L;

    private final java.util.Map<String, Long> lastRedeclareAttemptMs = new ConcurrentHashMap<>();

    private volatile MeterRegistry meterRegistry;

    public static String queueNameFor(String jobType) {
        return JOB_QUEUE_PREFIX + jobType;
    }

    /**
     * WO-REL-51: optional operator visibility — how many job types currently
     * run without a DLQ (see {@link #LEGACY_QUEUE_METRIC}). {@code required=false}:
     * contexts without a Micrometer registry (plain unit tests, minimal starters)
     * work exactly as before, just without the gauge.
     */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null && meterRegistry.find(LEGACY_QUEUE_METRIC).gauge() == null) {
            Gauge.builder(LEGACY_QUEUE_METRIC, legacyDeclared, Set::size)
                .description("Job-type queues pinned in legacy (pre-REL-45, no DLX) mode — DLQ inactive")
                .register(meterRegistry);
        }
    }

    /** WO-REL-51: has this job type's queue been pinned as legacy (406 path)? */
    public boolean isLegacyDeclared(String jobType) {
        if (jobType == null || jobType.isBlank()) return false;
        return legacyDeclared.contains(queueNameFor(jobType));
    }

    /** WO-REL-51: snapshot of the queue names currently pinned as legacy. */
    public Set<String> legacyDeclaredQueueNames() {
        return Set.copyOf(legacyDeclared);
    }

    /**
     * WO-REL-66: queue names this JVM has declared (work queues). DLQ names
     * ride along inside {@link #declare} and are intentionally NOT listed here —
     * a DLQ is never sent to directly, so its loss is healed by the same
     * redeclare that heals its work queue. Read-only snapshot for the
     * reconnect-redeclare listener and the topology monitor.
     */
    public Set<String> declaredQueueNames() {
        return Set.copyOf(declared);
    }

    /**
     * WO-REL-66: job types whose work queue this JVM has declared (derived
     * from {@link #declared} by stripping {@link #JOB_QUEUE_PREFIX}).
     * Read-only snapshot for the reconnect-redeclare listener.
     */
    public Set<String> declaredJobTypes() {
        Set<String> types = new java.util.HashSet<>();
        for (String queueName : declared) {
            if (queueName.startsWith(JOB_QUEUE_PREFIX)) {
                types.add(queueName.substring(JOB_QUEUE_PREFIX.length()));
            }
        }
        return Set.copyOf(types);
    }

    /**
     * WO-REL-66: single redeclare attempt after an unroutable return on the
     * send path (the broker lost the queue — e.g. recreated — while this JVM
     * still has it cached as declared, so {@link #declare} would be a no-op).
     *
     * <p>Semantics: REL-51 legacy-pinned queues stay pinned (terminal for this
     * JVM — a redeclare can never succeed); calls inside the debounce window
     * are skipped (no declare storm); otherwise the queue is force-redeclared
     * (cache dropped first — a plain {@link #declare} would early-return on
     * the intact cache and heal nothing) and the redeclare counter is bumped.
     * Never throws (same contract as {@link #declare}).
     *
     * <p>NOT a resend: redelivery comes from the outbox poller tick
     * (at-least-once), this only heals the topology. Safe to call from the
     * broker returns-callback (connection thread): bounded work, one broker
     * round-trip at most per window.
     *
     * @return true if a redeclare was attempted (false = legacy-pinned,
     *         debounced, or blank type — nothing healed)
     */
    public boolean redeclareForSend(String jobType) {
        if (jobType == null || jobType.isBlank()) return false;
        long now = System.currentTimeMillis();
        Long last = lastRedeclareAttemptMs.put(queueNameFor(jobType), now);
        if (last != null && now - last < REDECLARE_DEBOUNCE_MS) return false;
        return forceRedeclare(jobType, "returned");
    }

    /**
     * WO-REL-66: unconditional single redeclare of one job type — drops the
     * name from the {@link #declared} cache FIRST (without that a plain
     * {@link #declare} early-returns on the intact cache and heals nothing —
     * that is exactly why the reconnect listener must call this, not
     * {@code declare}), then declares for real. REL-51 legacy-pinned names
     * stay pinned (terminal for this JVM). Never throws.
     *
     * @return true if a redeclare was attempted (false = legacy-pinned or
     *         blank type)
     */
    public boolean forceRedeclare(String jobType, String trigger) {
        if (jobType == null || jobType.isBlank()) return false;
        String queueName = queueNameFor(jobType);
        // WO-REL-51: terminal for this JVM — redeclaring a legacy queue 406s
        // forever; only the operator migration (delete + redeclare, see the
        // runbook) changes the broker-side definition.
        if (legacyDeclared.contains(queueName)) return false;
        declared.remove(queueName);
        declare(jobType);
        countRedeclare(trigger);
        return true;
    }

    /**
     * WO-REL-66: bumps {@code zbpm.rabbit.topology.redeclare{trigger}}.
     * No-op when no Micrometer registry is wired (plain unit tests, minimal
     * starters) — same discipline as the legacy-queue gauge above.
     */
    void countRedeclare(String trigger) {
        MeterRegistry registry = meterRegistry;
        if (registry == null) return;
        try {
            registry.counter("zbpm.rabbit.topology.redeclare", "trigger", trigger).increment();
        } catch (Exception e) {
            log.debug("Could not record topology redeclare counter", e);
        }
    }

    @EventListener
    public void on(JobQueuesRequested event) {
        if (event.getJobTypes() == null) return;
        event.getJobTypes().forEach(this::declare);
    }

    /**
     * Declares the queue for this job type unless this JVM already did. Never throws: a broker
     * that is down at deployment/startup must not fail the deployment or the application start —
     * the failed name is dropped from the cache so the next attempt (including the lazy declare on
     * the send path) retries it.
     *
     * <p>WO-REL-45: the queue carries {@code x-dead-letter-exchange} (shared
     * {@link #JOBS_DLX}) + {@code x-dead-letter-routing-key} (its own DLQ name).
     * Same scheme as {@code COMPLETE_QUEUE} (see {@code RabbitConfiguration}).
     * The DLX (direct) + per-type DLQ are declared alongside — declaring an
     * existing exchange/queue with identical arguments is idempotent on the
     * broker. Names of existing queues/routing keys are UNCHANGED (criterion 3:
     * already-deployed external workers keep working).
     *
     * <p>Compatibility note: a queue first declared WITHOUT the DLX arguments
     * (pre-REL-45 broker state) keeps the old definition until an operator
     * deletes it — RabbitMQ 406s on redeclare-with-different-arguments.
     * WO-REL-51: that 406 is TERMINAL for this JVM (see {@link #legacyDeclared}):
     * the name stays cached, exactly one {@code warn} is logged, the failure is
     * NOT retried on every subsequent message, and the queue is counted in
     * {@link #LEGACY_QUEUE_METRIC}. Migration procedure:
     * {@code docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md}.
     * Documented in «Сервис-задачи: написание воркера» of
     * docs/guides/integration-quickstart.md (criterion 1, second half).
     */
    public void declare(String jobType) {
        if (jobType == null || jobType.isBlank()) return;
        String queueName = queueNameFor(jobType);
        if (!declared.add(queueName)) return;
        try {
            String dlqName = dlqNameFor(jobType);
            amqpAdmin.declareExchange(
                new org.springframework.amqp.core.DirectExchange(JOBS_DLX, true, false));
            amqpAdmin.declareQueue(QueueBuilder.durable(dlqName).build());
            amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(
                dlqName, org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                JOBS_DLX, dlqName, null));
            amqpAdmin.declareQueue(QueueBuilder.durable(queueName)
                .deadLetterExchange(JOBS_DLX)
                .deadLetterRoutingKey(dlqName)
                .build());
            log.info("Job queue {} declared (DLQ {})", queueName, dlqName);
        } catch (Exception e) {
            if (isPreconditionFailed(e)) {
                // WO-REL-51: the queue exists with the OLD (pre-REL-45, no-DLX)
                // definition. Retrying this on every message = 4 wasted broker
                // RPCs + an error-with-stack per message, forever, with zero
                // chance of success — only an operator migration (delete +
                // redeclare, see the runbook) changes the broker-side definition.
                // So: terminal for this JVM. The name STAYS in `declared` (the
                // send path becomes a no-op again), warn EXACTLY once.
                legacyDeclared.add(queueName);
                log.warn("Job queue {} exists with legacy arguments (no DLX — DLQ inactive "
                        + "for this job type); skipping further declares until migration. "
                        + "Migrate per docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md",
                    queueName);
            } else {
                declared.remove(queueName);
                log.error("Failed to declare job queue {} — will retry on next announcement or send",
                    queueName, e);
            }
        }
    }

    /**
     * WO-REL-51: is this failure a broker {@code 406 PRECONDITION_FAILED} on
     * inequivalent redeclare (queue exists with different arguments)?
     *
     * <p>Two independent signals, either is enough:
     * <ul>
     *   <li>structured: a {@link ShutdownSignalException} in the cause chain
     *   whose method reason is a channel/connection close with reply-code 406
     *   (this is what the broker actually sends; message text is NOT trusted —
     *   its spelling varies by client version, cf. WO-REL-45's 406 guard test);</li>
     *   <li>textual fallback: {@code PRECONDITION_FAILED}, or {@code 406} together
     *   with {@code inequivalent}, anywhere in the chained messages (covers client
     *   wrappers that don't expose the AMQP method, e.g. Spring's
     *   {@code AmqpIOException} built from a bare message).</li>
     * </ul>
     * A bare {@code 406} WITHOUT either keyword is NOT enough (could be a
     * different precondition failure that a retry might heal) — such failures
     * keep the old retry-on-next-message behaviour.
     */
    static boolean isPreconditionFailed(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ShutdownSignalException sse) {
                Method reason = sse.getReason();
                if (reason instanceof AMQP.Channel.Close channelClose
                    && channelClose.getReplyCode() == 406) {
                    return true;
                }
                if (reason instanceof AMQP.Connection.Close connectionClose
                    && connectionClose.getReplyCode() == 406) {
                    return true;
                }
            }
            String message = c.getMessage();
            if (message != null) {
                String upper = message.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("PRECONDITION_FAILED")) {
                    return true;
                }
                if (upper.contains("406") && upper.contains("INEQUIVALENT")) {
                    return true;
                }
            }
        }
        return false;
    }
}
