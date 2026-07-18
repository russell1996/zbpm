package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        job.run();
        verify(batchProcessor).findEligibleInstances(any(), eq(10));
        verify(batchProcessor, never()).deleteInstances(any());
    }
}
