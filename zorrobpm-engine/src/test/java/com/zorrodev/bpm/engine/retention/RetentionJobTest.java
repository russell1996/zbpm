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
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor, bpmMetrics);
    }

    @Test
    void disabledByDefault_doesNothing() {
        // ttlDays=0 (default) → run() should return immediately
        job.run();
        verifyNoInteractions(batchProcessor);
    }

    /**
     * WO-QW-5 (NEW2-12): дефолтный passBudgetMs — ненулевой (10 минут).
     * POF-мутация: `= 0` в RetentionConfig — этот тест КРАСНЫЙ.
     */
    @Test
    void defaultPassBudget_isNonZero() {
        assertThat(new RetentionConfig().getPassBudgetMs())
            .as("дефолтный бюджет прохода retention обязан быть ненулевым (WO-QW-5)")
            .isEqualTo(600_000L);
    }

    @Test
    void enabled_delegatesToBatchProcessor() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        // WO-REL-49: instance pass is a claim loop now — first call empty ends it.
        // eq(90) is ttlDays (fallbackDays), not batchSize: the claim carries no batch size.
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(), 0));
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(0);
        job.run();
        verify(batchProcessor).claimAndDeleteBatch(any(), eq(90), eq(10), any());
        verify(batchProcessor).deleteOrphanedBoundaryTimers(any(), eq(10));
        // The old split path (select commits before delete) must not be used anymore:
        // it let two replicas select the same IDs after lock release (audit §5.2).
        verify(batchProcessor, never()).findEligibleInstances(any(), anyInt(), anyInt());
        verify(batchProcessor, never()).deleteInstances(any());
    }

    @Test
    void enabled_cleansOrphanedBoundaryTimersInBatches() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(), 0));
        // two full batches then a short one → loop must stop after the short batch
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(10, 10, 4);
        job.run();
        verify(batchProcessor, times(3)).deleteOrphanedBoundaryTimers(any(), eq(10));
    }

    @Test
    void enabled_batchSizeZero_doesNotLoopForever() {
        config.setTtlDays(90);
        config.setBatchSize(0);
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(0), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(), 0));
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
     * WO-PERF-8 (R2) intent, preserved under the WO-REL-49 claim loop: one instance per
     * transaction — the job must claim+delete one id per call, never a whole batch in one
     * call (that single call was one transaction for up to batchSize instances).
     */
    @Test
    void enabled_deletesInstancesOnePerTransaction() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        // Two claimed instances, then an empty claim ends the loop.
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(id1), 5),
                new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(id2), 7),
                new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(), 0));
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(0);

        job.run();

        verify(batchProcessor, times(3)).claimAndDeleteBatch(any(), eq(90), eq(10), any());
        // The old split path must stay unused (see enabled_delegatesToBatchProcessor).
        verify(batchProcessor, never()).findEligibleInstances(any(), anyInt(), anyInt());
        verify(batchProcessor, never()).deleteInstances(any());
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

        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(2), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(java.util.List.of(), 0));
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
        // WO-REL-50: exactly one stuck row reported.
        verify(bpmMetrics).setRetentionSubmissionsStuck(1);
    }
}
