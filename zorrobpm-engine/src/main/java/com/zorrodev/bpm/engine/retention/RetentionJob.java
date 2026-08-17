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
            List<UUID> eligible = batchProcessor.findEligibleInstances(cutoff, config.getBatchSize());
            if (eligible.isEmpty()) break;

            int deleted = batchProcessor.deleteInstances(eligible);
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
        int submissionsDeleted = 0;
        while (true) {
            List<UUID> eligibleSubmissions = batchProcessor.findEligibleSubmissions(cutoff, config.getBatchSize());
            if (eligibleSubmissions.isEmpty()) break;

            for (UUID submissionId : eligibleSubmissions) {
                try {
                    submissionsDeleted += batchProcessor.deleteSubmission(submissionId);
                } catch (RuntimeException e) {
                    log.warn("Retention: failed to delete process submission {} — continuing with the rest", submissionId, e);
                }
            }

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
