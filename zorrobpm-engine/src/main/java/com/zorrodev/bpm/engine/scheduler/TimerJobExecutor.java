package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fires a single due timer in its own transaction, so one failing timer cannot roll back the
 * whole poll batch. Separate bean (not a self-invoked method) so the {@link Transactional} proxy
 * actually applies.
 */
@Component
@RequiredArgsConstructor
public class TimerJobExecutor {

    private final DBService dbService;
    private final ActivityService activityService;

    @Transactional
    public void fire(UUID timerJobId, UUID activityId, String boundaryElementId) {
        dbService.markTimerJobFired(timerJobId);
        if (boundaryElementId == null) {
            activityService.signal(activityId, List.of());
        } else {
            activityService.fireBoundaryTimer(activityId, boundaryElementId);
        }
    }
}
