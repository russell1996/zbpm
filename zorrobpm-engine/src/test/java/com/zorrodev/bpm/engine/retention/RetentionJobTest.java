package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

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
        when(batchProcessor.findEligibleInstances(any(), eq(10))).thenReturn(java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(0);
        job.run();
        verify(batchProcessor).findEligibleInstances(any(), eq(10));
        verify(batchProcessor).deleteOrphanedBoundaryTimers(any(), eq(10));
        verify(batchProcessor, never()).deleteInstances(any());
    }

    @Test
    void enabled_cleansOrphanedBoundaryTimersInBatches() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        when(batchProcessor.findEligibleInstances(any(), eq(10))).thenReturn(java.util.List.of());
        // two full batches then a short one → loop must stop after the short batch
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(10))).thenReturn(10, 10, 4);
        job.run();
        verify(batchProcessor, times(3)).deleteOrphanedBoundaryTimers(any(), eq(10));
    }

    @Test
    void enabled_batchSizeZero_doesNotLoopForever() {
        config.setTtlDays(90);
        config.setBatchSize(0);
        when(batchProcessor.findEligibleInstances(any(), eq(0))).thenReturn(java.util.List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(0))).thenReturn(0);
        // Preemptive timeout: with the pre-fix guard "deleted < batchSize" the loop never exits
        // (0 < 0 is false) and run() spins forever issuing DELETE LIMIT 0 — the timeout kills it.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());
        verify(batchProcessor, times(1)).deleteOrphanedBoundaryTimers(any(), eq(0));
    }
}
