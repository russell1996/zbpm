package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

        executor.fire(jobId, activityId, null);

        verify(dbService).markTimerJobFired(jobId);
        verify(activityService).signal(eq(activityId), any());
    }

    @Test
    void executor_firesBoundaryTimerWhenBoundarySet() {
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        executor.fire(jobId, activityId, "boundary1");

        verify(dbService).markTimerJobFired(jobId);
        verify(activityService).fireBoundaryTimer(activityId, "boundary1");
    }

    @Test
    void scheduler_firesEachDueJob_andIsolatesFailures() {
        TimerJobExecutor executorMock = org.mockito.Mockito.mock(TimerJobExecutor.class);
        TimerStartJobExecutor startExecutorMock = org.mockito.Mockito.mock(TimerStartJobExecutor.class);
        TimerScheduler scheduler = new TimerScheduler(dbService, executorMock, startExecutorMock);

        TimerJob bad = job();
        TimerJob good = job();
        when(dbService.findDueTimerJobs(any())).thenReturn(List.of(bad, good));
        when(dbService.findDueTimerStartJobs(any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(executorMock).fire(eq(bad.getId()), any(), any());

        scheduler.fireDueTimers();

        // the failing job must not stop the next one from firing
        verify(executorMock).fire(eq(bad.getId()), any(), any());
        verify(executorMock).fire(eq(good.getId()), any(), any());
    }

    private static TimerJob job() {
        TimerJob j = new TimerJob();
        j.setId(UUID.randomUUID());
        j.setActivityId(UUID.randomUUID());
        return j;
    }
}
