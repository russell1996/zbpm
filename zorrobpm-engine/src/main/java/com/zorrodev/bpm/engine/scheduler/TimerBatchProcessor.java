package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Transactional batch processor for timer jobs.
 * Separated from TimerScheduler to avoid self-invocation proxy issue (P-18):
 * @Transactional only works when called through a Spring proxy (i.e. from a different bean).
 *
 * Uses SELECT … FOR UPDATE SKIP LOCKED (L6 fix) so that row locks are held until commit,
 * preventing two pollers from picking the same due timers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerBatchProcessor {

    private final DBService dbService;
    private final TimerJobExecutor timerJobExecutor;
    private final TimerStartJobExecutor timerStartJobExecutor;

    @Value("${zorrobpm.timer.batch-size:100}")
    private int batchSize;

    @Transactional
    public void processBatch() {
        Instant now = Instant.now();

        List<TimerJob> dueJobs = dbService.findDueTimerJobsLocked(now, batchSize);
        for (TimerJob job : dueJobs) {
            try {
                timerJobExecutor.fire(job);
            } catch (Exception e) {
                log.error("Failed to fire timer job {} (activity {})", job.getId(), job.getActivityId(), e);
            }
        }

        List<TimerStartJob> dueStartJobs = dbService.findDueTimerStartJobsLocked(now, batchSize);
        for (TimerStartJob job : dueStartJobs) {
            try {
                timerStartJobExecutor.fire(job.getId(), job.getProcessDefinitionId(), job.getElementId());
            } catch (Exception e) {
                log.error("Failed to fire timer start job {} (definition {})", job.getId(), job.getProcessDefinitionId(), e);
            }
        }
    }
}
