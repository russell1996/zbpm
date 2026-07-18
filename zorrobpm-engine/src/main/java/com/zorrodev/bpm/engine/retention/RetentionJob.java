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
 * Scheduled retention job that cleans up terminal process instances.
 * Disabled by default (ttlDays=0). When enabled, periodically finds and deletes
 * COMPLETED/CANCELLED instances older than TTL, with fail-safe guards.
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

        if (totalDeleted > 0) {
            log.info("Retention: completed — {} total rows deleted", totalDeleted);
        }
    }
}
