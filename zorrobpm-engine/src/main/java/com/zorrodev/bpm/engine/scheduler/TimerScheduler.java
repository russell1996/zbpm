package com.zorrodev.bpm.engine.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Polls for due timer jobs and fires each one (resuming the parked token via signal).
 * Delegates to TimerBatchProcessor (@Transactional) so the poll loop itself stays
 * NON-transactional: candidate selection runs in its own SHORT transaction and
 * SKIP LOCKED row locks are released as soon as the SELECT returns (WO-REL-13) —
 * each fire() then commits in its own REQUIRES_NEW transaction.
 *
 * WO-PERF-6 (P-1): offloads batch to dedicated {@code timerDispatcherExecutor}
 * (1-2 threads) so the scheduling pool (Outbox/Watchdog) never blocks on timer
 * I/O. The actual job parallelism lives on {@code timerExecutor} (4-8 threads)
 * inside {@code TimerBatchProcessor} — dispatcher and workers never share a pool.
 * An {@code AtomicBoolean} guard prevents overlapping batches: if a batch
 * outlives the fixedDelay (e.g. >5s under load), the next tick is skipped
 * and logged instead of piling up rejections.
 */
@Slf4j
@Component
public class TimerScheduler {

    private final TimerBatchProcessor batchProcessor;
    private final Executor timerDispatcherExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

    public TimerScheduler(TimerBatchProcessor batchProcessor,
                          @Qualifier("timerDispatcherExecutor") Executor timerDispatcherExecutor) {
        this.batchProcessor = batchProcessor;
        this.timerDispatcherExecutor = timerDispatcherExecutor;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.engine.timer-poll-interval-ms:5000}")
    public void fireDueTimers() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Timer batch still running, skipping tick");
            return;
        }
        timerDispatcherExecutor.execute(() -> {
            try {
                batchProcessor.processBatch();
            } catch (Exception e) {
                log.error("Timer batch failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    // visible for test
    boolean isRunning() {
        return running.get();
    }
}
