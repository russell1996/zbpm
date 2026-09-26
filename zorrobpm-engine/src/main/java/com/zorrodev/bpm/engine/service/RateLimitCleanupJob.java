package com.zorrodev.bpm.engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WO-SCALE-2: periodically cleans old rate-limit bucket rows so the
 * table does not grow unboundedly. A bucket row is eligible for deletion
 * once it has not been touched for over a day ({@code PgRateLimiter}
 * recreates any missing row on next first touch, so deletion is always
 * safe) — this just reclaims disk, expiry itself is implicit in the
 * window logic.
 *
 * <p>Uses the same {@code @Scheduled(fixedDelayString)} pattern as
 * {@link com.zorrodev.bpm.engine.retention.RetentionJob}.
 */
@Slf4j
@Component
public class RateLimitCleanupJob {

    private final PgRateLimiter pgRateLimiter;

    public RateLimitCleanupJob(PgRateLimiter pgRateLimiter) {
        this.pgRateLimiter = pgRateLimiter;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.rate-limit.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int deleted = pgRateLimiter.deleteExpired();
            if (deleted > 0) {
                log.info("RateLimitCleanupJob: deleted {} expired bucket rows", deleted);
            }
        } catch (Exception e) {
            log.warn("RateLimitCleanupJob failed — continuing", e);
        }
    }
}
