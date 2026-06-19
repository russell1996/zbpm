package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fires a single due timer start job in its own transaction: marks it fired and starts a new
 * process instance at the timer start element. Separate bean so the {@link Transactional} proxy applies.
 */
@Component
@RequiredArgsConstructor
public class TimerStartJobExecutor {

    private final DBService dbService;
    private final ActivityService activityService;

    @Transactional
    public void fire(UUID timerStartJobId, UUID processDefinitionId, String elementId) {
        dbService.markTimerStartJobFired(timerStartJobId);
        activityService.startProcessInstanceFromStartEvent(processDefinitionId, elementId, List.of());
    }
}
