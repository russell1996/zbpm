package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Fires a single due timer in its own transaction, so one failing timer cannot roll back the
 * whole poll batch. Separate bean (not a self-invoked method) so the {@link Transactional} proxy
 * actually applies.
 *
 * WO-REL-13 (R-03): REQUIRES_NEW — the fire transaction is fully independent of the poll loop
 * (which no longer holds a transaction at all) and of every other job. A failure rolls back only
 * this job's claim and side effects; the row stays fired=false and is retried by the next poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerJobExecutor {

    private final DBService dbService;
    private final ActivityService activityService;
    private final ProcessInstanceRepository processInstanceRepository;
    private final com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;

    // WO-ENG-4 / WO-REL-14: explicit business zone for timer cycle/cron re-arm resolution
    @Value("${zorrobpm.business-timezone:Asia/Almaty}")
    private ZoneId businessZone;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fire(TimerJob job) {
        if (!dbService.claimTimerJob(job.getId())) {
            return; // Already claimed by another node
        }
        // WO-OBS-1: lag between due and actual fire, sampled at claim (floored at zero inside).
        if (job.getDueAt() != null) {
            bpmMetrics.recordTimerLag(java.time.Duration.between(job.getDueAt(), java.time.Instant.now()));
        }
        if (job.getEventSubprocessId() != null) {
            // timer-started event sub-process: no host activity
            activityService.fireEventSubprocessTimer(job.getProcessInstanceId(), job.getEventSubprocessId());
        } else if (job.getBoundaryElementId() == null) {
            // Intermediate catch event
            Integer remaining = job.getRemainingCount();
            if (remaining != null && remaining > 0) {
                // L1 FIX: lock the process instance row to serialise with cancel.
                // Without this lock, re-arm could read stale "not cancelled" state while
                // cancel is committing, creating a zombie timer_job.
                ProcessInstanceEntity pi = processInstanceRepository.findByIdForUpdate(job.getProcessInstanceId()).orElse(null);
                if (pi != null && (pi.isCancelled() || pi.getCompletedAt() != null)) {
                    log.debug("Skipping re-arm: process instance {} is cancelled/completed", job.getProcessInstanceId());
                    return;
                }
                // WO-REL-14 (R-04, defect 1): re-arm from the REAL cycle expression (persisted at
                // scheduling time), not a hardcoded "R/PT0S" (which fired every remaining
                // repetition back-to-back instead of respecting the interval). Reference point is
                // the PREVIOUS dueAt, not Instant.now(), so execution latency cannot accumulate
                // drift across repetitions.
                if (job.getExpression() == null) {
                    // Pre-migration row with no persisted expression: cannot safely resolve the
                    // real interval, so end the cycle here rather than reintroduce a burst.
                    log.warn("Timer job {} has remaining={} but no persisted expression (pre-WO-REL-14 row) — ending cycle instead of bursting", job.getId(), remaining);
                    activityService.signal(job.getActivityId(), List.of());
                    return;
                }
                Instant next = TimerExpressions.firstOccurrence(job.getExpression(), job.getDueAt(), businessZone);
                dbService.createTimerJob(job.getActivityId(), next, null, remaining - 1, job.getExpression(), job.getProcessInstanceId());
                return;
            }
            activityService.signal(job.getActivityId(), List.of());
        } else {
            activityService.fireBoundaryTimer(job.getActivityId(), job.getBoundaryElementId());
        }
    }
}
