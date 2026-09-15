package com.zorrodev.bpm.engine.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * WO-REL-27: stuck service-task watchdog.
 * Finds service tasks stuck in CREATED beyond dispatch-timeout and raises an
 * incident SERVICE_TASK_DISPATCH_TIMEOUT idempotently. Does NOT re-dispatch the job.
 * Multi-instance safe via FOR UPDATE SKIP LOCKED batch selection.
 *
 * <p>WO-REL-35 (F08): this scheduler holds NO {@code @Transactional} method of its
 * own — the batch runs in {@code StuckServiceTaskBatchProcessor} (a DIFFERENT
 * Spring bean, called through the proxy). The old self-invoked
 * {@code checkStuckTasks()} → {@code processBatch()} pair silently ran WITHOUT
 * a transaction: SELECT FOR UPDATE + incident creation were never atomic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckServiceTaskWatchdog {

    private final StuckServiceTaskBatchProcessor batchProcessor;

    @Value("${zorrobpm.servicetask.dispatch-timeout:15m}")
    private Duration dispatchTimeout;

    @Value("${zorrobpm.servicetask.watchdog-batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${zorrobpm.servicetask.watchdog-interval-ms:60000}")
    public void checkStuckTasks() {
        if (dispatchTimeout == null || dispatchTimeout.isZero() || dispatchTimeout.isNegative()) {
            log.debug("StuckServiceTaskWatchdog disabled (dispatch-timeout=0)");
            return;
        }
        try {
            int total = batchProcessor.processBatch(Instant.now().minus(dispatchTimeout), batchSize, dispatchTimeout);
            if (total > 0) {
                log.info("StuckServiceTaskWatchdog: processed {} stuck tasks", total);
            }
        } catch (Exception e) {
            log.error("StuckServiceTaskWatchdog failed", e);
        }
    }

    // exposed for tests / PgIT to override timeout without Spring rewire
    void setDispatchTimeout(Duration timeout) {
        this.dispatchTimeout = timeout;
    }

    void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
