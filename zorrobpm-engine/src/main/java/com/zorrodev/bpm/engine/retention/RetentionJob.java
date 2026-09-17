package com.zorrodev.bpm.engine.retention;

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

    @Scheduled(fixedDelayString = "${zorrobpm.engine.retention.poll-interval-ms:3600000}")
    public void run() {
        if (config.getTtlDays() <= 0) {
            return; // disabled
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(config.getTtlDays()));
        log.info("Retention: looking for terminal instances completed before {} (TTL={}d)", cutoff, config.getTtlDays());

        int totalDeleted = 0;
        while (true) {
            // WO-ENG-17: cutoff по каждому определению (собственный TTL или
            // глобальный как фолбэк); orphan/submission-пассы ниже — без изменений
            // (у orphan нет определения, у submission — свой TTL-скоуп).
            List<UUID> eligible = batchProcessor.findEligibleInstances(
                Instant.now(), config.getTtlDays(), config.getBatchSize());
            if (eligible.isEmpty()) break;

            // WO-PERF-8: one instance per transaction — deleteInstances is @Transactional
            // (up to 12 DELETEs), so deleting the whole batch in one call held locks for
            // up to batchSize instances at once. Per-id calls keep each transaction to a
            // single instance's rows; loop/exit semantics and fail-fast are unchanged.
            int deleted = 0;
            for (UUID id : eligible) {
                deleted += batchProcessor.deleteInstances(java.util.List.of(id));
            }
            totalDeleted += deleted;
            log.info("Retention: deleted batch of {} instances ({} rows total so far)", eligible.size(), totalDeleted);

            if (eligible.size() < config.getBatchSize()) break; // last batch
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
        int submissionsDeleted = 0;
        java.util.Set<UUID> skippedSubmissionIds = new java.util.HashSet<>();
        while (true) {
            // Снапшот skip-set на опрос: опрос видит фиксированное множество, мутации
            // этого прохода (новые плохие строки) влияют только на следующий опрос.
            List<UUID> eligibleSubmissions = batchProcessor.findEligibleSubmissions(
                cutoff, config.getBatchSize(), java.util.Set.copyOf(skippedSubmissionIds));
            if (eligibleSubmissions.isEmpty()) break;

            int pageDeleted = 0;
            for (UUID submissionId : eligibleSubmissions) {
                try {
                    int gone = batchProcessor.deleteSubmission(submissionId);
                    pageDeleted += gone;
                    submissionsDeleted += gone;
                } catch (RuntimeException e) {
                    log.warn("Retention: failed to delete process submission {} — continuing with the rest", submissionId, e);
                    skippedSubmissionIds.add(submissionId);
                }
            }

            if (pageDeleted == 0) break; // полная страница без прогресса — выход, не вечный цикл
            if (eligibleSubmissions.size() < config.getBatchSize()) break; // last batch
        }
        if (submissionsDeleted > 0) {
            log.info("Retention: deleted {} terminal process submissions", submissionsDeleted);
        }

        if (totalDeleted > 0) {
            log.info("Retention: completed — {} total rows deleted", totalDeleted);
        }
    }
}
