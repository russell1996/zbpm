package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
    public void fire(TimerJob job) {
        if (!dbService.claimTimerJob(job.getId())) {
            return; // Already claimed by another node
        }
        if (job.getEventSubprocessId() != null) {
            // timer-started event sub-process: no host activity
            activityService.fireEventSubprocessTimer(job.getProcessInstanceId(), job.getEventSubprocessId());
        } else if (job.getBoundaryElementId() == null) {
            // Intermediate catch event
            Integer remaining = job.getRemainingCount();
            if (remaining != null && remaining > 0) {
                // Bounded timer with remaining fires: re-arm without completing the event
                Instant next = TimerExpressions.firstOccurrence("R/PT0S", Instant.now());
                dbService.createTimerJob(job.getActivityId(), next, null, remaining - 1);
                return;
            }
            activityService.signal(job.getActivityId(), List.of());
        } else {
            activityService.fireBoundaryTimer(job.getActivityId(), job.getBoundaryElementId());
        }
    }
}
