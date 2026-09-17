package com.zorrodev.bpm.engine.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the retention job that cleans up terminal process instances.
 * Disabled by default (ttlDays=0) — enable by setting zorrobpm.engine.retention.ttl-days to a positive value.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "zorrobpm.engine.retention")
public class RetentionConfig {
    /** TTL in days. Terminal instances older than this are eligible for cleanup. 0 = disabled. */
    private int ttlDays = 0;

    /** Poll interval in milliseconds. */
    private long pollIntervalMs = 3600_000; // 1 hour

    /**
     * WO-PERF-8: batch size for deletion (max instances per poll). 25, not 100:
     * {@code RetentionBatchProcessor.deleteInstances} runs up to 12 DELETEs in one
     * transaction per batch, so 100 instances held row locks far longer than needed.
     * Smaller chunks = shorter transactions at the same throughput (the job loops
     * until no eligible rows remain).
     */
    private int batchSize = 25;
}
