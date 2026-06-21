package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Polls for due timer jobs and fires each one (resuming the parked token via signal).
 * Replaces the missing scheduler that left timer events unexecutable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerScheduler {

    private final DBService dbService;
    private final TimerJobExecutor executor;
    private final TimerStartJobExecutor startExecutor;

    @Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
    public void fireDueTimers() {
        for (TimerJob job : dbService.findDueTimerJobs(Instant.now())) {
            try {
                executor.fire(job);
            } catch (Exception e) {
                log.error("Failed to fire timer job {} (activity {})", job.getId(), job.getActivityId(), e);
            }
        }
        for (TimerStartJob job : dbService.findDueTimerStartJobs(Instant.now())) {
            try {
                startExecutor.fire(job.getId(), job.getProcessDefinitionId(), job.getElementId());
            } catch (Exception e) {
                log.error("Failed to fire timer start job {} (definition {})", job.getId(), job.getProcessDefinitionId(), e);
            }
        }
    }
}
