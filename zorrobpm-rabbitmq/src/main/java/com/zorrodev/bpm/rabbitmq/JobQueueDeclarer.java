package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobQueuesRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
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
     */
    public void declare(String jobType) {
        if (jobType == null || jobType.isBlank()) return;
        String queueName = queueNameFor(jobType);
        if (!declared.add(queueName)) return;
        try {
            amqpAdmin.declareQueue(new Queue(queueName, true));
            log.info("Job queue {} declared", queueName);
        } catch (Exception e) {
            declared.remove(queueName);
            log.error("Failed to declare job queue {} — will retry on next announcement or send",
                queueName, e);
        }
    }
}
