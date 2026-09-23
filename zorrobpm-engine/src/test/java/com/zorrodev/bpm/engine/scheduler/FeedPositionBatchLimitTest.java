package com.zorrodev.bpm.engine.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-48 (N14), structural half: every eligible SELECT in the production
 * assigner carries the batch LIMIT. Plain unit test (no Spring, no DB) — it
 * asserts a property of the shipped source, readable by reviewer and verifier
 * alike. The named mutation that must fail it: removing the LIMIT from any
 * eligible SELECT in {@code FeedPositionAssigner}.
 *
 * <p>Separated from {@code FeedPositionBoundedBatchPgIT} on purpose: the PG
 * class needs the Spring/PG context (behavioral drain proof), this one needs
 * nothing — and, crucially, it can go RED on unfixed code WITHOUT a database
 * (POF below), while the PG class proves the drain on fixed code WITH one.
 *
 * <p>POF: on unfixed code there is no {@code LIMIT} in the assign path at
 * all — this test goes RED (found 0, expected &gt;= 3).
 */
class FeedPositionBatchLimitTest {

    @Test
    void batchLimit_presentInEligibleSelects() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/zorrodev/bpm/engine/scheduler/FeedPositionAssigner.java"));
        long limitedSelects = source.lines()
            .filter(l -> l.contains("LIMIT \" + FEED_BATCH_SIZE"))
            .count();
        assertThat(limitedSelects)
            .as("every eligible SELECT in FeedPositionAssigner must carry the batch LIMIT")
            .isGreaterThanOrEqualTo(3);
    }
}
