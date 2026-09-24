package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobQueuesRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueBuilder;
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

    private final AmqpAdmin amqpAdmin;

    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    public static String queueNameFor(String jobType) {
        return JOB_QUEUE_PREFIX + jobType;
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
     * deletes it — RabbitMQ 406s on redeclare-with-different-arguments, which
     * lands in the catch below (declare retried later, deployment never fails).
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
            declared.remove(queueName);
            log.error("Failed to declare job queue {} — will retry on next announcement or send",
                queueName, e);
        }
    }
}
