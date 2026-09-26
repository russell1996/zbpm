package com.zorrodev.bpm.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WO-OBS-1 (revival): business metrics for the engine, Micrometer + Prometheus.
 * Revived from the abandoned July branch — every meter below is WIRED to a real prod
 * path on current master (the July cut registered 15 but only called ~9; the dead ones
 * got honest hooks here, see below). Additive one-liners only, no behavior change.
 * <ul>
 *   <li>process lifecycle: started/completed + active gauge (RuntimeServiceImpl start,
 *       ProcessInstanceDbOperationsImpl complete/cancel);</li>
 *   <li>process failures + stuck tokens: every incident creation/completion
 *       (IncidentDbOperationsImpl — the single choke all incident paths flow through).
 *       Mapping, documented: the engine's failure signal IS the incident (token parked
 *       ERROR + operator attention); one open incident ≈ one stuck token;</li>
 *   <li>script bulkhead: rejections/timeouts + pool/queue gauges (ScriptServiceImpl);</li>
 *   <li>outbox: published on broker ACK (OutboxDeliveryResultListener — true delivery,
 *       not enqueue), failed on quarantine (both quarantine paths), backlog/quarantine
 *       gauges sampled per batch, broker NACKs counted as rabbit publish failures;</li>
 *   <li>timer lag: fire time minus due time at claim (TimerJobExecutor), floored at zero.</li>
 * </ul>
 */
@Component
public class BpmMetrics {

    // --- Process lifecycle counters ---
    private final Counter processStarted;
    private final Counter processCompleted;
    private final Counter processFailed;

    // --- Process gauges ---
    private final AtomicLong activeInstances = new AtomicLong(0);
    private final AtomicLong stuckTokens = new AtomicLong(0);
    private final AtomicLong stuckServiceTasks = new AtomicLong(0);
    private final AtomicLong usertaskAgeMax = new AtomicLong(0);

    // --- Script bulkhead gauges + counters ---
    private final Counter scriptRejected;
    private final Counter scriptTimeout;
    private final AtomicLong scriptActiveWorkers = new AtomicLong(0);
    private final AtomicLong scriptQueueDepth = new AtomicLong(0);
    // WO-REL-46: конфигурированный размер FEEL-пула — знаменатель для Grafana-правила
    // насыщения (active >= size), чтобы порог не был захардкожен в алерте отдельно
    // от application.properties. Домент тот же, что у соседних script-метрик выше, —
    // отдельный бин под один gauge плодить нечем (см. эскалацию god-class в agent-to-cto.md).
    private final AtomicLong scriptPoolSize = new AtomicLong(0);

    // --- Outbox gauges + counters ---
    private final Counter outboxPublished;
    private final Counter outboxFailed;
    private final AtomicLong outboxBacklog = new AtomicLong(0);
    private final AtomicLong outboxQuarantine = new AtomicLong(0);
    // WO-REL-50: terminal submissions that failed cleanup in the last retention pass
    // (stuck head rows — FK-blocked or otherwise undeletable). Set (not incremented)
    // once per pass by RetentionJob, same shape as setOutboxQuarantine.
    private final AtomicLong retentionSubmissionsStuck = new AtomicLong(0);

    // --- Rabbit publish failures ---
    private final Counter rabbitPublishFailures;

    // --- Timer lag ---
    private final Timer timerLag;

    // --- Activity transitions (WO-QW-2) ---
    private final Counter activityTransitionIgnored;

    /**
     * WO-REL-48: feed-position backlog visibility. NOT new injected
     * dependencies — these three meters live in BpmMetrics because that is
     * the domain this class already owns (Micrometer registry → Prometheus;
     * every meter here is registered in the same constructor and pushed from
     * a single call-site in FeedPositionAssigner). A separate component for
     * three gauges of the same registry would be a распил ради распила, not a
     * god-class cut: BpmMetrics' fields are all same-shaped meter holders
     * (Counter/Timer/AtomicLong) with no logic, no branching, no cross-domain
     * behavior — the WO-DEBT-1 god-class failure mode (16-18 injected
     * collaborators + half the domain model in methods) does not apply.
     * (G19: this comment is the explicit god-class/распил escalation.)
     */
    private final AtomicLong feedBacklog = new AtomicLong(0);
    private final AtomicLong feedAgeMaxSeconds = new AtomicLong(0);
    private final Timer feedAssignDuration;

    public BpmMetrics(MeterRegistry registry) {
        // Process lifecycle
        this.processStarted = Counter.builder("zbpm.process.started")
            .description("Total process instances started")
            .register(registry);
        this.processCompleted = Counter.builder("zbpm.process.completed")
            .description("Total process instances completed")
            .register(registry);
        this.processFailed = Counter.builder("zbpm.process.failed")
            .description("Failure events (incident raised — the engine failure signal)")
            .register(registry);

        // Active instances gauge (no clamp: restart with in-flight work may dip below
        // zero as old instances drain — honest pairing, converges back by itself)
        Gauge.builder("zbpm.process.instances.active", activeInstances, AtomicLong::doubleValue)
            .description("Currently active process instances")
            .register(registry);

        // Stuck tokens gauge (open incidents)
        Gauge.builder("zbpm.tokens.stuck", stuckTokens, AtomicLong::doubleValue)
            .description("Tokens in stuck/error state (open incidents)")
            .register(registry);

        // WO-REL-27: stuck service tasks beyond dispatch-timeout
        Gauge.builder("zbpm.servicetask.stuck", stuckServiceTasks, AtomicLong::doubleValue)
            .description("Service tasks stuck in CREATED beyond dispatch-timeout")
            .register(registry);

        // WO-OBS-3: oldest open user task age (seconds) — DLQ depth is covered by
        // rabbitmq_queue_messages{queue="zorrobpm.complete-service-task.dlq"} from
        // WO-OBS-2 (rabbitmq_prometheus), no custom zbpm.dlq.depth needed.
        Gauge.builder("zbpm.usertask.age.max", usertaskAgeMax, AtomicLong::doubleValue)
            .description("Age of oldest open user task (seconds)")
            .register(registry);

        // Script bulkhead
        this.scriptRejected = Counter.builder("zbpm.script.rejected")
            .description("Script evaluations rejected by bulkhead")
            .register(registry);
        this.scriptTimeout = Counter.builder("zbpm.script.timeout")
            .description("Script evaluations that timed out")
            .register(registry);
        Gauge.builder("zbpm.script.pool.active", scriptActiveWorkers, AtomicLong::doubleValue)
            .description("Active script evaluation threads")
            .register(registry);
        Gauge.builder("zbpm.script.pool.queue", scriptQueueDepth, AtomicLong::doubleValue)
            .description("Script pool queue depth")
            .register(registry);
        Gauge.builder("zbpm.script.pool.size", scriptPoolSize, AtomicLong::doubleValue)
            .description("Configured script evaluation pool size")
            .register(registry);

        // Outbox
        this.outboxPublished = Counter.builder("zbpm.outbox.published")
            .description("Outbox entries confirmed by broker (ACK)")
            .register(registry);
        this.outboxFailed = Counter.builder("zbpm.outbox.failed")
            .description("Outbox entries that failed permanently")
            .register(registry);
        Gauge.builder("zbpm.outbox.backlog", outboxBacklog, AtomicLong::doubleValue)
            .description("Outbox entries pending (published=false)")
            .register(registry);
        Gauge.builder("zbpm.outbox.quarantine", outboxQuarantine, AtomicLong::doubleValue)
            .description("Outbox entries in quarantine (status=FAILED)")
            .register(registry);

        // WO-REL-50: stuck cleanup rows, visible to the operator instead of silently
        // blocking the submissions pass.
        Gauge.builder("zbpm.retention.submissions.stuck", retentionSubmissionsStuck, AtomicLong::doubleValue)
            .description("Terminal process submissions that failed cleanup in the last retention pass")
            .register(registry);

        // Rabbit
        this.rabbitPublishFailures = Counter.builder("zbpm.rabbit.publish.failures")
            .description("RabbitMQ publish failures (broker NACK)")
            .register(registry);

        // Timer lag
        this.timerLag = Timer.builder("zbpm.timer.lag")
            .description("Lag between timer due and actual fire")
            .register(registry);

        // WO-REL-48: feed-position backlog visibility — size/age gauges sampled
        // on every assign tick (before AND after the batch) + tick duration.
        Gauge.builder("zbpm.feed.backlog", feedBacklog, AtomicLong::doubleValue)
            .description("Events still without feed_position (unassigned backlog)")
            .register(registry);
        Gauge.builder("zbpm.feed.age.max", feedAgeMaxSeconds, AtomicLong::doubleValue)
            .description("Age of oldest event without feed_position (seconds)")
            .register(registry);
        this.feedAssignDuration = Timer.builder("zbpm.feed.assign.duration")
            .description("Feed position assign tick duration")
            .register(registry);

        // WO-QW-2: idempotent status-guard no-ops in CompletionService
        // (duplicate/late completions). Counter with a reason tag so future
        // ignore-reasons can reuse the same meter.
        this.activityTransitionIgnored = Counter.builder("zbpm.activity.transition.ignored")
            .description("Activity completions ignored by the idempotent status guard")
            .tag("reason", "stale_status")
            .register(registry);
    }

    // --- Process lifecycle ---
    public void processStarted() { processStarted.increment(); }
    public void processCompleted() { processCompleted.increment(); }
    public void processFailed() { processFailed.increment(); }
    public void incrementActiveInstances() { activeInstances.incrementAndGet(); }
    public void decrementActiveInstances() { activeInstances.decrementAndGet(); }
    public void incrementStuckTokens() { stuckTokens.incrementAndGet(); }
    public void decrementStuckTokens() { stuckTokens.decrementAndGet(); }

    public void setStuckServiceTasks(long count) { stuckServiceTasks.set(count); }

    public void setUsertaskAgeMax(long seconds) { usertaskAgeMax.set(seconds); }

    // --- Script bulkhead ---
    public void scriptRejected() { scriptRejected.increment(); }
    public void scriptTimeout() { scriptTimeout.increment(); }

    public void updateScriptPoolMetrics(ThreadPoolExecutor executor) {
        scriptActiveWorkers.set(executor.getActiveCount());
        scriptQueueDepth.set(executor.getQueue().size());
    }

    public void setScriptPoolSize(long size) { scriptPoolSize.set(size); }

    // --- Outbox ---
    public void outboxPublished() { outboxPublished.increment(); }
    public void outboxFailed() { outboxFailed.increment(); }
    public void setOutboxBacklog(long count) { outboxBacklog.set(count); }
    public void setOutboxQuarantine(long count) { outboxQuarantine.set(count); }

    // --- Retention (WO-REL-50) ---
    public void setRetentionSubmissionsStuck(long count) { retentionSubmissionsStuck.set(count); }

    // --- Rabbit ---
    public void rabbitPublishFailed() { rabbitPublishFailures.increment(); }

    // --- Timer lag (floored at zero — early fires are not negative lag) ---
    public void recordTimerLag(Duration lag) {
        timerLag.record(lag.isNegative() ? Duration.ZERO : lag);
    }

    // --- Feed position assigner (WO-REL-48, meters declared above) ---
    public void setFeedBacklog(long count) { feedBacklog.set(count); }
    public void setFeedAgeMaxSeconds(long seconds) { feedAgeMaxSeconds.set(seconds); }
    public void recordFeedAssignDuration(Duration duration) { feedAssignDuration.record(duration); }

    // --- Activity transitions (WO-QW-2) ---
    public void activityTransitionIgnored(String reason) {
        // Single pre-registered reason tag today ("stale_status"); the parameter
        // keeps the call-site honest if a second reason ever appears.
        if ("stale_status".equals(reason)) activityTransitionIgnored.increment();
    }
}
