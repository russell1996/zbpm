package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled retention job that cleans up terminal process instances and (WO-ACL-3) terminal
 * process submissions. Disabled by default (ttlDays=0). When enabled, periodically finds and
 * deletes COMPLETED/CANCELLED instances and APPROVED/REJECTED submissions older than TTL,
 * with fail-safe guards.
 *
 * Delegates to RetentionBatchProcessor (@Transactional) to ensure proper transaction
 * boundaries and FK-safe cascade deletion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final RetentionConfig config;
    private final RetentionBatchProcessor batchProcessor;
    // WO-REL-50: stuck-row visibility (gauge) — the loop owner reports per-pass failures.
    private final BpmMetrics bpmMetrics;

    @Scheduled(fixedDelayString = "${zorrobpm.engine.retention.poll-interval-ms:3600000}")
    public void run() {
        if (config.getTtlDays() <= 0) {
            return; // disabled
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(config.getTtlDays()));
        log.info("Retention: looking for terminal instances completed before {} (TTL={}d)", cutoff, config.getTtlDays());

        int totalDeleted = 0;
        // WO-REL-54: claim+delete ПАЧКОЙ в одной транзакции
        // (RetentionBatchProcessor.claimAndDeleteBatch): SELECT ... LIMIT batch
        // FOR UPDATE SKIP LOCKED + все DELETEs пачки в той же транзакции —
        // тот же механизм REL-49, шире окно: на пачку — один eligible-SELECT
        // вместо batchSize штук, DELETEs — IN (:ids) вместо поштучных.
        // TTL-DISTINCT — раз за прогон (loadDistinctTtls), не на каждую пачку.
        // Дедлайн — между пачками: начатая пачка коммитится целиком.
        long deadlineNanos = config.getPassBudgetMs() > 0
            ? System.nanoTime() + config.getPassBudgetMs() * 1_000_000L
            : Long.MAX_VALUE;
        List<Integer> distinctTtls = batchProcessor.loadDistinctTtls();
        Instant passNow = Instant.now();
        int instancesDeleted = 0;
        while (true) {
            // WO-ENG-17: cutoff по каждому определению (собственный TTL или
            // глобальный как фолбэк); orphan/submission-пассы ниже — без изменений
            // (у orphan нет определения, у submission — свой TTL-скоуп).
            RetentionBatchProcessor.ClaimedBatch done =
                batchProcessor.claimAndDeleteBatch(passNow, config.getTtlDays(),
                    config.getBatchSize(), distinctTtls);
            if (done.instanceIds().isEmpty()) break; // last batch

            instancesDeleted += done.instanceIds().size();
            totalDeleted += done.rowsDeleted();
            // WO-REL-54: гонка pre-query/main-query на DISTINCT TTL (деплой с
            // новым TTL между loadDistinctTtls и пачкой) безопасна — пропущенные
            // строки доберутся следующим проходом; список НЕ перезапрашивается
            // внутри прохода (иначе задача 2 не выполнена).
            if (System.nanoTime() >= deadlineNanos) {
                log.info("Retention: pass budget exhausted after {} instances — continuing next run",
                    instancesDeleted);
                break;
            }
        }
        if (instancesDeleted > 0) {
            log.info("Retention: deleted {} instances ({} rows total so far)", instancesDeleted, totalDeleted);
        }

        // WO-PERF-3: pre-fix boundary jobs carry NULL process_instance_id and are invisible to
        // deleteInstances (NULL NOT IN (:ids) never matches). Clean them in bounded batches here,
        // not at migration time — no startup block, no changelog lock, TTL-gated by the same cutoff.
        int orphanDeleted = 0;
        while (true) {
            int deleted = batchProcessor.deleteOrphanedBoundaryTimers(cutoff, config.getBatchSize());
            orphanDeleted += deleted;
            // batchSize=0 would make "deleted < batchSize" never true (0 < 0 is false) and the loop
            // would spin forever issuing DELETE LIMIT 0; "deleted <= 0" makes the exit unconditional.
            if (deleted <= 0 || deleted < config.getBatchSize()) break; // last batch
        }

        if (orphanDeleted > 0) {
            log.info("Retention: deleted {} orphaned fired boundary timer jobs", orphanDeleted);
        }

        // WO-ACL-3 criterion 8: purge terminal process submissions (APPROVED/REJECTED) past the
        // TTL. Rows are deleted one by one, each in its own transaction, so one failing row
        // (concurrent FK race, lock) logs a warning and the pass continues (P-42).
        // WO-REL-33 F37: та же «застревание на первой странице», что F09 в WO-REL-35:
        // если ни одна строка ПОЛНОЙ страницы не удалилась — выходим (как
        // IdempotencyCleanupJob: pageDeleted==0 → выход), НО курсор продвигаем мимо
        // «плохих» строк через skip-set, а не бросаем их навсегда: страница опрашивается
        // с исключением уже виденных неудаляемых id, так что прогресс есть всегда —
        // либо удаления, либо рост skip-set, либо пустая страница. Без skip-set один
        // вечно неудаляемый рядок останавливал бы всю очередь навсегда.
        // WO-REL-50 (остаток F37 — starvation следующих страниц): pageDeleted == 0 больше
        // НЕ прекращает проход. Прогресс теперь идёт по skip-курсору, а не по числу удалений:
        // каждая итерация либо удаляет, либо приращивает skip-set, и опрос всегда исключает
        // уже виденные плохие id — хвост обрабатывается на том же проходе, даже если вся
        // первая страница неудаляема. Короткий опрос (< batchSize) по-прежнему значит
        // «невиденных eligible-строк больше нет» — единственный выход, кроме пустого опроса.
        int submissionsDeleted = 0;
        java.util.Set<UUID> skippedSubmissionIds = new java.util.HashSet<>();
        java.util.Set<UUID> stuckSubmissionIds = new java.util.LinkedHashSet<>();
        while (true) {
            // Снапшот skip-set на опрос: опрос видит фиксированное множество, мутации
            // этого прохода (новые плохие строки) влияют только на следующий опрос.
            List<UUID> eligibleSubmissions = batchProcessor.findEligibleSubmissions(
                cutoff, config.getBatchSize(), java.util.Set.copyOf(skippedSubmissionIds));
            if (eligibleSubmissions.isEmpty()) break;

            for (UUID submissionId : eligibleSubmissions) {
                try {
                    int gone = batchProcessor.deleteSubmission(submissionId);
                    submissionsDeleted += gone;
                } catch (RuntimeException e) {
                    log.warn("Retention: failed to delete process submission {} — continuing with the rest", submissionId, e);
                    skippedSubmissionIds.add(submissionId);
                    stuckSubmissionIds.add(submissionId);
                }
            }

            if (eligibleSubmissions.size() < config.getBatchSize()) break; // last batch
        }
        // WO-REL-50: застрявшие строки видны оператору (gauge + warn выше), а не молча
        // блокируют job. Gauge — состояние последнего прохода: чистый проход сбрасывает в 0.
        bpmMetrics.setRetentionSubmissionsStuck(stuckSubmissionIds.size());
        if (!stuckSubmissionIds.isEmpty()) {
            log.warn("Retention: {} terminal process submission(s) stuck undeleted this pass: {}",
                stuckSubmissionIds.size(), stuckSubmissionIds);
        }
        if (submissionsDeleted > 0) {
            log.info("Retention: deleted {} terminal process submissions", submissionsDeleted);
        }

        if (totalDeleted > 0) {
            log.info("Retention: completed — {} total rows deleted", totalDeleted);
        }
    }
}
