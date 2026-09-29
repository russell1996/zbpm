package com.zorrodev.bpm.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
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
