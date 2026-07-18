package com.zorrodev.bpm.engine.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due timer jobs and fires each one (resuming the parked token via signal).
 * Delegates to TimerBatchProcessor (@Transactional) so that FOR UPDATE SKIP LOCKED
 * row locks are held until commit (L6 fix).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerScheduler {

    private final TimerBatchProcessor batchProcessor;

    @Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
    public void fireDueTimers() {
        batchProcessor.processBatch();
    }
}
