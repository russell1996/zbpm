package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Timers.
 */
public interface TimerDbOperations {

    UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId, Integer remainingCount, String expression, UUID processInstanceId);

    UUID createEventSubprocessTimerJob(UUID processInstanceId, Instant dueAt, String eventSubprocessId);

    void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt);

    void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt, Integer remainingCount);

    /**
     * WO-C8-35 (CR-09, ШАГ 2/B1): this instance's still-ARMED timer triggers (fired = false) that
     * continue elsewhere \u2014 boundary timer / event-sub-process timer. Catch timers excluded.
     */
    List<com.zorrodev.bpm.engine.entity.TimerJobEntity> findArmedTimerJobs(UUID processInstanceId);

    void deleteTimerJobsByProcessInstanceId(UUID processInstanceId);

    void deleteTimerStartJobsByKey(String processKey);

    List<TimerJob> findDueTimerJobs(Instant now);

    List<TimerJob> findDueTimerJobsLocked(Instant now, int batchSize);

    List<TimerStartJob> findDueTimerStartJobs(Instant now);

    List<TimerStartJob> findDueTimerStartJobsLocked(Instant now, int batchSize);

    boolean claimTimerJob(UUID timerJobId);

    boolean claimTimerStartJob(UUID timerStartJobId);

    void recordTimerJobError(UUID timerJobId, String errorMessage);

    void recordTimerStartJobError(UUID timerStartJobId, String errorMessage);
}
