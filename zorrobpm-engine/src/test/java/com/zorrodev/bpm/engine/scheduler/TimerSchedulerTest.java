package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimerSchedulerTest {

    @Mock private DBService dbService;
    @Mock private ActivityService activityService;
    @InjectMocks private TimerJobExecutor executor;

    @Test
    void executor_firesJobAndSignalsActivity() {
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        when(dbService.claimTimerJob(jobId)).thenReturn(true);

        executor.fire(job(jobId, activityId, null));

        verify(dbService).claimTimerJob(jobId);
        verify(activityService).signal(eq(activityId), any());
    }

    @Test
    void executor_firesBoundaryTimerWhenBoundarySet() {
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        when(dbService.claimTimerJob(jobId)).thenReturn(true);

        executor.fire(job(jobId, activityId, "boundary1"));

        verify(dbService).claimTimerJob(jobId);
        verify(activityService).fireBoundaryTimer(activityId, "boundary1");
    }

    @Test
    void executor_skipsFireWhenClaimFails() {
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        when(dbService.claimTimerJob(jobId)).thenReturn(false);

        executor.fire(job(jobId, activityId, null));

        verify(dbService).claimTimerJob(jobId);
        org.mockito.Mockito.verifyNoInteractions(activityService);
    }

    @Test
    void batchProcessor_firesEachDueJob_andIsolatesFailures() throws Exception {
        DBService dbServiceMock = org.mockito.Mockito.mock(DBService.class);
        TimerJobExecutor executorMock = org.mockito.Mockito.mock(TimerJobExecutor.class);
        TimerStartJobExecutor startExecutorMock = org.mockito.Mockito.mock(TimerStartJobExecutor.class);
        TimerBatchProcessor batchProcessor = new TimerBatchProcessor(dbServiceMock, executorMock, startExecutorMock, Runnable::run);
        Field f = TimerBatchProcessor.class.getDeclaredField("batchSize");
        f.setAccessible(true);
        f.setInt(batchProcessor, 100);

        TimerJob bad = job();
        TimerJob good = job();
        when(dbServiceMock.findDueTimerJobsLocked(any(), anyInt())).thenReturn(List.of(bad, good));
        when(dbServiceMock.findDueTimerStartJobsLocked(any(), anyInt())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(executorMock).fire(eq(bad));

        batchProcessor.processBatch();

        // the failing job must not stop the next one from firing
        verify(executorMock).fire(eq(bad));
        verify(executorMock).fire(eq(good));
    }

    private static TimerJob job(UUID id, UUID activityId, String boundaryElementId) {
        TimerJob j = new TimerJob();
        j.setId(id);
        j.setActivityId(activityId);
        j.setBoundaryElementId(boundaryElementId);
        return j;
    }

    private static TimerJob job() {
        TimerJob j = new TimerJob();
        j.setId(UUID.randomUUID());
        j.setActivityId(UUID.randomUUID());
        return j;
    }
}
