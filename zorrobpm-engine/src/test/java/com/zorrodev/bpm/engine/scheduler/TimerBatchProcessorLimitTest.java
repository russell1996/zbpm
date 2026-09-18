package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WO-REL-11: TimerBatchProcessor passes batchSize to DB queries.
 * Proves the processor respects the batch limit contract.
 */
@ExtendWith(MockitoExtension.class)
class TimerBatchProcessorLimitTest {

    @Mock private DBService dbService;

    private static void setBatchSize(TimerBatchProcessor processor, int size) throws Exception {
        Field f = TimerBatchProcessor.class.getDeclaredField("batchSize");
        f.setAccessible(true);
        f.setInt(processor, size);
    }

    @Test
    void processBatch_passesBatchSizeToDb() throws Exception {
        TimerJobExecutor executor = mock(TimerJobExecutor.class);
        TimerStartJobExecutor startExecutor = mock(TimerStartJobExecutor.class);
        TimerBatchProcessor processor = new TimerBatchProcessor(dbService, executor, startExecutor, Runnable::run);
        setBatchSize(processor, 50);

        when(dbService.findDueTimerJobsLocked(any(), eq(50))).thenReturn(List.of());
        when(dbService.findDueTimerStartJobsLocked(any(), eq(50))).thenReturn(List.of());

        processor.processBatch();

        verify(dbService).findDueTimerJobsLocked(any(), eq(50));
        verify(dbService).findDueTimerStartJobsLocked(any(), eq(50));
    }

    @Test
    void processBatch_withLimit_processesOnlyReturnedJobs() throws Exception {
        TimerJobExecutor executor = mock(TimerJobExecutor.class);
        TimerStartJobExecutor startExecutor = mock(TimerStartJobExecutor.class);
        TimerBatchProcessor processor = new TimerBatchProcessor(dbService, executor, startExecutor, Runnable::run);
        setBatchSize(processor, 100);

        TimerJob job1 = new TimerJob();
        job1.setId(UUID.randomUUID());
        job1.setActivityId(UUID.randomUUID());
        TimerJob job2 = new TimerJob();
        job2.setId(UUID.randomUUID());
        job2.setActivityId(UUID.randomUUID());

        // DB returns only 2 jobs (batch limit enforced at SQL level)
        when(dbService.findDueTimerJobsLocked(any(), eq(100))).thenReturn(List.of(job1, job2));
        when(dbService.findDueTimerStartJobsLocked(any(), eq(100))).thenReturn(List.of());

        processor.processBatch();

        // Only 2 jobs fired, not more
        verify(executor, times(1)).fire(eq(job1));
        verify(executor, times(1)).fire(eq(job2));
        verify(executor, times(2)).fire(any(TimerJob.class));
    }
}
