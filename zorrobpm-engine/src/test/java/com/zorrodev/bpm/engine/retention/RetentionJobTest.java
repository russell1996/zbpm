package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionJobTest {

    @Mock private RetentionBatchProcessor batchProcessor;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor);
    }

    @Test
    void disabledByDefault_doesNothing() {
        // ttlDays=0 (default) → run() should return immediately
        job.run();
        verifyNoInteractions(batchProcessor);
    }

    @Test
    void enabled_delegatesToBatchProcessor() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(10))).thenReturn(java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(0);
        job.run();
        verify(batchProcessor).findEligibleInstances(any(), anyInt(), eq(10));
        verify(batchProcessor).deleteOrphanedBoundaryTimers(any(), eq(10));
        verify(batchProcessor, never()).deleteInstances(any());
    }

    @Test
    void enabled_cleansOrphanedBoundaryTimersInBatches() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(10))).thenReturn(java.util.List.of());
        // two full batches then a short one → loop must stop after the short batch
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(10, 10, 4);
        job.run();
        verify(batchProcessor, times(3)).deleteOrphanedBoundaryTimers(any(), eq(10));
    }

    @Test
    void enabled_batchSizeZero_doesNotLoopForever() {
        config.setTtlDays(90);
        config.setBatchSize(0);
        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(0))).thenReturn(java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(0))).thenReturn(0);
        // Preemptive timeout: with the pre-fix guard "deleted < batchSize" the loop never exits
        // (0 < 0 is false) and run() spins forever issuing DELETE LIMIT 0 — the timeout kills it.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());
        verify(batchProcessor, times(1)).deleteOrphanedBoundaryTimers(any(), eq(0));
    }

    /**
     * WO-PERF-8 (R1): smaller default chunk shortens the retention transaction
     * (up to 12 DELETEs per batch — 100 instances held locks far longer than needed).
     */
    @Test
    void defaultBatchSize_is25() {
        assertThat(new RetentionConfig().getBatchSize()).isEqualTo(25);
    }

    /**
     * WO-PERF-8 (R2): one instance per transaction — the job must call
     * {@code deleteInstances} once per id, never with the whole batch (that single
     * call was one transaction for up to batchSize instances).
     */
    @Test
    void enabled_deletesInstancesOnePerTransaction() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(10)))
            .thenReturn(java.util.List.of(id1, id2), java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(0);
        when(batchProcessor.deleteInstances(java.util.List.of(id1))).thenReturn(5);
        when(batchProcessor.deleteInstances(java.util.List.of(id2))).thenReturn(7);
        // Mutant shape (pre-fix single call with the whole batch): stubbed leniently
        // so a regressed job fails the explicit never()-verify below instead of
        // erroring on a strict-stubbing mismatch.
        lenient().when(batchProcessor.deleteInstances(java.util.List.of(id1, id2))).thenReturn(12);

        job.run();

        verify(batchProcessor).deleteInstances(java.util.List.of(id1));
        verify(batchProcessor).deleteInstances(java.util.List.of(id2));
        verify(batchProcessor, never()).deleteInstances(java.util.List.of(id1, id2));
    }

    /**
     * WO-ACL-3 criterion 8 (P-42): terminal process submissions are purged in batches and a
     * single failing row (locked, FK race) must NOT abort the pass — the remaining rows of the
     * batch are still deleted and the job completes normally.
     */
    @Test
    void enabled_deletesTerminalSubmissionsSurvivingSingleRowFailure() {
        config.setTtlDays(90);
        config.setBatchSize(2);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(2))).thenReturn(java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(2))).thenReturn(0);
        // one full batch (2) + one partial (1), then an empty poll ends the loop
        when(batchProcessor.findEligibleSubmissions(any(), eq(2), any()))
            .thenReturn(java.util.List.of(id1, id2, id3), java.util.List.of());
        // row id2 is broken — the job must log a warning and continue with id3
        when(batchProcessor.deleteSubmission(id1)).thenReturn(1);
        when(batchProcessor.deleteSubmission(id2)).thenThrow(new RuntimeException("row locked"));
        when(batchProcessor.deleteSubmission(id3)).thenReturn(1);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());

        verify(batchProcessor).deleteSubmission(id1);
        verify(batchProcessor).deleteSubmission(id2);
        verify(batchProcessor).deleteSubmission(id3);
    }
}
