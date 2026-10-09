package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Batch processor for timer jobs.
 * Separated from TimerScheduler to avoid self-invocation proxy issue (P-18):
 * @Transactional only works when called through a Spring proxy (i.e. from a different bean).
 *
 * WO-REL-13 (R-03): the poll loop itself is NOT transactional. Candidate selection
 * ({@link DBService#findDueTimerJobsLocked}) runs in its own SHORT transaction (SKIP LOCKED row
 * locks are released as soon as the SELECT returns), and each {@code fire()} runs in its own
 * REQUIRES_NEW transaction inside the executor. A failing job therefore rolls back ONLY its own
 * transaction — the other jobs of the batch commit independently. Double execution of one job is
 * still impossible: either the SKIP LOCKED selection skips a concurrently locked row, or the
 * atomic CAS claim (fired=false → true) inside the fire transaction loses for the second poller.
 * Each failure is recorded per-job (attempts++/last_error) instead of being silently logged.
 *
 * WO-PERF-6 (P-1): runs on dedicated {@code timerExecutor} (4-8 threads), not the shared
 * scheduling pool (size 4, shared with Outbox/Watchdog). Jobs of one batch run in parallel
 * via {@code CompletableFuture.allOf} — head-of-queue latency drops from N*fire to max(fire).
 * Adaptive: batchSize is still the upper bound (100), but parallelism hides per-job latency.
 */
@Slf4j
@Component
public class TimerBatchProcessor {

    private final DBService dbService;
    private final TimerJobExecutor timerJobExecutor;
    private final TimerStartJobExecutor timerStartJobExecutor;
    private final Executor timerExecutor;
    /**
     * WO-ENG-35 (NEW2-16): admission-гейт script-пула. Каждый fire ждёт место
     * ЗДЕСЬ (poll-поток, вне REQUIRES_NEW-транзакции fire) и держит лизу всё
     * время fire: sustained-перегрузка даёт ту же ошибку fire (запись +
     * повтор позже), но ожидание окна не удерживает соединение. Отказ гейта
     * (503) ловится тем же catch ниже — как обычная ошибка fire.
     */
    private final ScriptService scriptService;

    @Value("${zorrobpm.timer.batch-size:100}")
    private int batchSize;

    public TimerBatchProcessor(DBService dbService,
                               TimerJobExecutor timerJobExecutor,
                               TimerStartJobExecutor timerStartJobExecutor,
                               @Qualifier("timerExecutor") Executor timerExecutor,
                               ScriptService scriptService) {
        this.dbService = dbService;
        this.timerJobExecutor = timerJobExecutor;
        this.timerStartJobExecutor = timerStartJobExecutor;
        this.timerExecutor = timerExecutor;
        this.scriptService = scriptService;
    }

    public void processBatch() {
        Instant now = Instant.now();

        List<TimerJob> dueJobs = dbService.findDueTimerJobsLocked(now, batchSize);
        List<CompletableFuture<Void>> futures = new ArrayList<>(dueJobs.size());
        for (TimerJob job : dueJobs) {
            futures.add(CompletableFuture.runAsync(() -> {
                // WO-ENG-35: лиза — до fire (см. поле); try — общий с fire,
                // отказ гейта идёт тем же путём ошибки fire.
                try (AdmissionLease ignored = scriptService.admitOutsideTx()) {
                    timerJobExecutor.fire(job);
                } catch (Exception e) {
                    log.error("Failed to fire timer job {} (activity {})", job.getId(), job.getActivityId(), e);
                    try {
                        dbService.recordTimerJobError(job.getId(), e.getMessage());
                    } catch (Exception rec) {
                        log.error("Failed to record timer job {} error, it will retry without attempts bookkeeping", job.getId(), rec);
                    }
                }
            }, timerExecutor));
        }

        List<TimerStartJob> dueStartJobs = dbService.findDueTimerStartJobsLocked(now, batchSize);
        for (TimerStartJob job : dueStartJobs) {
            futures.add(CompletableFuture.runAsync(() -> {
                // WO-ENG-35: см. выше — та же лиза до fire.
                try (AdmissionLease ignored = scriptService.admitOutsideTx()) {
                    timerStartJobExecutor.fire(job.getId(), job.getProcessDefinitionId(), job.getElementId(), job.getDueAt(), job.getRemainingCount());
                } catch (Exception e) {
                    log.error("Failed to fire timer start job {} (definition {})", job.getId(), job.getProcessDefinitionId(), e);
                    try {
                        dbService.recordTimerStartJobError(job.getId(), e.getMessage());
                    } catch (Exception rec) {
                        log.error("Failed to record timer start job {} error, it will retry without attempts bookkeeping", job.getId(), rec);
                    }
                }
            }, timerExecutor));
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
}
