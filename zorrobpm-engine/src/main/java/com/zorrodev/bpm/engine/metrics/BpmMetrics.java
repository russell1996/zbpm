package com.zorrodev.bpm.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /**
     * WO-ENG-35: сколько admission-ожиданий всё ещё произошло ВНУТРИ транзакции
     * вызывающего (негейтованные пути + смешанный трафик + fallback при
     * мгновенном отказе). Операторский сигнал: в идеале 0 после гейтования
     * входов; рост = какой-то путь обходит гейт и держит соединение.
     */
    private final Counter scriptAdmissionInTx;
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
    // WO-C8-36 (F-5): счётчик ОДИН на причину, а не один на всё. РаньшеMeter с
    // жёстким .tag("reason","stale_status") принимал ещё и stale_phase, и
    // duplicate_completion, то есть в Prometheus phased-игноры и отказы дедупа были
    // неотличимы от stale_status — а счётчик добавлен именно затем, чтобы оператор
    // ВИДЕЛ причину (E-3a).
    private final Map<String, Counter> activityTransitionIgnoredByReason;

    /** Известные причины игнора перехода. Неизвестные — по-прежнему не считаются. */
    static final List<String> ACTIVITY_TRANSITION_IGNORE_REASONS =
        List.of("stale_status", "stale_phase", "duplicate_completion", "legacy_null_phase");

    /**
     * WO-C8-36 (M-3): сколько completion'ов пришло БЕЗ идентификатора вызова
     * (legacy-путь). Fail-open семантика на legacy-пути принята CTO осознанно
     * (E-3) как совместимость со старыми воркерами, но «принято как риск» и
     * «невидимо» — разные вещи: без счётчика во время rolling-обновления
     * нельзя измерить, сколько трафика идёт вне защиты CR-01, и отличить
     * намеренный обход от обычного REST-трафика. Именно эту невидимость
     * закрывает счётчик (плюс WARN на каждый legacy-проход).
     */
    private final Counter legacyUnphasedCompletion;

    // --- Completion replay (WO-AUDIT-8, A-NEW4-15) ---
    // Повторный complete — тихий 2xx-успех без следа. Один meter, серия на вид
    // задачи (kind=user_task/service_task): оператор видит долю повторов
    // (at-least-once дубликаты брокера vs реальные двойные клики).
    private final Map<String, Counter> completionReplayByKind;

    /** Виды задач для {@code zbpm.completion.replay}. Неизвестные — не считаются. */
    static final List<String> COMPLETION_REPLAY_KINDS = List.of("user_task", "service_task");

    // --- SSE bridge (WO-REL-56, part B) ---
    private final Counter sseForeignSequenceDropped;

    // --- WO-AUDIT-7: retention чистка events/outbox (тот же домен, что
    // retentionSubmissionsStuck выше — отдельный бин ради трёх счётчиков
    // плодить нечем; G19: поля — same-shaped meter holders без логики).
    private final Counter retentionEventsDeletedTotal;
    private final Counter retentionOutboxDeletedTotal;
    private final Timer retentionPassDuration;

    /**
     * WO-REL-66 (B): quarantined-loop cut visibility — unroutable domain-event
     * notifications dropped WITH accounting instead of quarantined recursively.
     * One series per event type (same shape as the activity-transition
     * ignore-reasons above); unknown types are not counted.
     */
    private final Map<String, Counter> domainEventUnroutableByType;

    /** Event types counted by {@link #domainEventUnroutable}. */
    static final List<String> DOMAIN_EVENT_UNROUTABLE_TYPES = List.of("outbox.quarantined");
    // --- SSE retry-lane (WO-AUDIT-8, A-NEW4-10) ---
    // Глубина очереди retry-lane SseEventStreamService: порог в алерте — рядом
    // с Saturated-warn'ами; gauge обновляется при каждом обращении к lane.
    // Живёт здесь (тот же домен реестра → Prometheus), отдельный бин под один
    // gauge — распил ради распила.
    private final AtomicLong sseRetryQueueDepth = new AtomicLong(0);

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
        this.scriptAdmissionInTx = Counter.builder("zbpm.script.admission.in_tx")
            .description("Script admission waits that happened inside the caller's transaction (ungated path or mixed-traffic fallback)")
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
        this.legacyUnphasedCompletion = Counter.builder("zbpm.completion.legacy.unphased")
            .description("WO-C8-36 (M-3): service-task completions accepted on the legacy "
                + "null-dispatchPhase path (no call identifier — old worker or REST). "
                + "These are OUTSIDE the CR-01 exact-match guard by construction (E-3).")
            .register(registry);

        // WO-C8-36 (F-5): по счётчику на причину — тег перестаёт врать.
        Map<String, Counter> ignoredByReason = new LinkedHashMap<>();
        for (String reason : ACTIVITY_TRANSITION_IGNORE_REASONS) {
            ignoredByReason.put(reason, Counter.builder("zbpm.activity.transition.ignored")
                .description("Activity completions ignored by the idempotent status/phase guard. "
                    + "One series per reason — a shared 'stale_status' tag made phased-ignores "
                    + "and dedup rejections indistinguishable from it.")
                .tag("reason", reason)
                .register(registry));
        }
        this.activityTransitionIgnoredByReason = Map.copyOf(ignoredByReason);

        // WO-AUDIT-8 (A-NEW4-15): повторные complete — наблюдаемые, а не тихие.
        // Та же форма, что activityTransitionIgnoredByReason выше: один meter,
        // серия на kind. Реестр — тот же домен этого класса (Micrometer →
        // Prometheus), отдельный бин под два счётчика — распил ради распила.
        Map<String, Counter> replayByKind = new LinkedHashMap<>();
        for (String kind : COMPLETION_REPLAY_KINDS) {
            replayByKind.put(kind, Counter.builder("zbpm.completion.replay")
                .description("Duplicate task completions absorbed by the idempotent guard "
                    + "(2xx + alreadyCompleted=true, no state change). One series per task kind.")
                .tag("kind", kind)
                .register(registry));
        }
        this.completionReplayByKind = Map.copyOf(replayByKind);

        // WO-REL-56 (part B): foreign sequence dropped, visible to the
        // operator instead of silently skipped (pre-REL-55 behavior was a
        // bare warn; REL-55 dispatched it with a foreign-domain SSE id).
        this.sseForeignSequenceDropped = Counter.builder("zbpm.sse.foreign.dropped")
            .description("SSE live events dropped: sequence has no row in this DB (foreign installation/test publish)")
            .register(registry);

        // WO-REL-66 (B): one series per dropped-as-unroutable notification type.
        Map<String, Counter> unroutableByType = new LinkedHashMap<>();
        for (String type : DOMAIN_EVENT_UNROUTABLE_TYPES) {
            unroutableByType.put(type, Counter.builder("zbpm.domain.event.unroutable")
                .description("Domain-event notifications dropped as unroutable "
                    + "(no route on zorrobpm.events) instead of quarantined recursively. "
                    + "A growing series means nobody is bound to that notification.")
                .tag("type", type)
                .register(registry));
        }
        this.domainEventUnroutableByType = Map.copyOf(unroutableByType);
        // WO-AUDIT-7: сколько строк events/outbox снёс retention (суммарно по
        // проходам) + длительность одного прохода каждой таблицы.
        this.retentionEventsDeletedTotal = Counter.builder("zbpm.retention.events.deleted.total")
            .description("Events rows deleted by the events retention pass")
            .register(registry);
        this.retentionOutboxDeletedTotal = Counter.builder("zbpm.retention.outbox.deleted.total")
            .description("Outbox rows deleted by the outbox retention pass")
            .register(registry);
        this.retentionPassDuration = Timer.builder("zbpm.retention.pass.duration")
            .description("Duration of one events/outbox retention table pass")
            .tag("table", "events-outbox")
            .register(registry);

        // WO-AUDIT-8 (A-NEW4-10): retry-lane queue depth — насыщение lane
        // видно в Grafana, а не только в Saturated-warn'ах лога.
        Gauge.builder("zbpm.sse.retry.queue", sseRetryQueueDepth, AtomicLong::doubleValue)
            .description("SSE retry-lane queue depth (deferred cursor resolutions + pump retries)")
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
    public void scriptAdmissionInTx() { scriptAdmissionInTx.increment(); }

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

    /**
     * WO-C8-36 (M-3): legacy-проход без идентификатора вызова. Считает и
     * fail-open путь (совместимость), и осознанный обход защиты CR-01 — их
     * различает уж вызывающий код, а не метрика.
     */
    public void legacyUnphasedCompletion() {
        legacyUnphasedCompletion.increment();
    }

    // --- Activity transitions (WO-QW-2 + WO-C8-36 F-5) ---
    /**
     * Причины: {@code stale_status} (WO-QW-2 — активность уже в терминальном
     * статусе), {@code stale_phase} (не тот индекс/фаза вызова), {@code
     * duplicate_completion} (тот же {@code completionId} уже обработан — durable
     * дедуп H-2), {@code legacy_null_phase} (сообщение без идентификатора вызова,
     * fail-open путь E-3). Каждая — ОТДЕЛЬНАЯ серия: общий счётчик с одним тегом
     * делал три разные причины неразличимыми, а счётчик добавлен именно ради
     * видимости причины.
     */
    public void activityTransitionIgnored(String reason) {
        Counter counter = activityTransitionIgnoredByReason.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    // --- Completion replay (WO-AUDIT-8, A-NEW4-15) ---
    /**
     * Повторный complete задачи вида {@code kind} ({@code user_task} /
     * {@code service_task}): guard поглотил дубликат, состояние не менялось,
     * ответ — 2xx + {@code alreadyCompleted=true}.
     */
    public void completionReplayTotal(String kind) {
        Counter counter = completionReplayByKind.get(kind);
        if (counter != null) {
            counter.increment();
        }
    }

    // --- SSE bridge (WO-REL-56, part B) ---
    public void sseForeignSequenceDropped() { sseForeignSequenceDropped.increment(); }

    // --- Quarantine-notification drops (WO-REL-66, part B) ---
    /**
     * Counts a dropped-as-unroutable notification of a known type. Unknown
     * types are not counted (same discipline as
     * {@link #activityTransitionIgnored}).
     */
    public void domainEventUnroutable(String type) {
        Counter counter = domainEventUnroutableByType.get(type);
        if (counter != null) {
            counter.increment();
        }
    }
    // --- WO-AUDIT-7: retention чистка events/outbox ---
    public void retentionEventsDeleted(long rows) { retentionEventsDeletedTotal.increment(rows); }
    public void retentionOutboxDeleted(long rows) { retentionOutboxDeletedTotal.increment(rows); }
    public void recordRetentionPassDuration(Duration duration) { retentionPassDuration.record(duration); }
    public void setSseRetryQueueDepth(long depth) { sseRetryQueueDepth.set(depth); }
}
