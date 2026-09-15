package com.zorrodev.bpm.engine.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Polls for due timer jobs and fires each one (resuming the parked token via signal).
 * Delegates to TimerBatchProcessor (@Transactional) so that FOR UPDATE SKIP LOCKED
 * row locks are held until commit (L6 fix).
 *
 * WO-PERF-6 (P-1): offloads batch to dedicated {@code timerExecutor} (4-8 threads),
 * not the shared scheduling pool (size 4, also used by Outbox/Watchdog). The
 * scheduler thread returns immediately after dispatch — it never blocks on
 * per-job I/O.
 */
@Slf4j
@Component
public class TimerScheduler {

    private final TimerBatchProcessor batchProcessor;
    private final Executor timerExecutor;

    public TimerScheduler(TimerBatchProcessor batchProcessor,
                          @Qualifier("timerExecutor") Executor timerExecutor) {
        this.batchProcessor = batchProcessor;
        this.timerExecutor = timerExecutor;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
    public void fireDueTimers() {
        timerExecutor.execute(() -> {
            try {
                batchProcessor.processBatch();
            } catch (Exception e) {
                log.error("Timer batch failed", e);
            }
        });
    }
}
