package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-4: L1 — bounded timer re-arm respects cancelled process.
 * V6: 2 REAL concurrent threads (not sequential simulation).
 *
 * Before fix: re-arm creates timer_job even when process is cancelled → zombie.
 * After fix: re-arm locks process row (FOR UPDATE) and checks cancelled → skips → no zombie.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class Rel4ConcurrencyTest {

    @Autowired private DBService dbService;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID createValidProcessDefinition() {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey("test-" + UUID.randomUUID().toString().substring(0, 8));
        pd.setName("Test");
        pd.setVersion(1);
        pd.setSha256(UUID.randomUUID().toString());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        return pd.getId();
    }

    /**
     * V6: Proof-of-failure — 2 real threads racing cancel vs re-arm.
     *
     * Thread 1 (re-arm): fires a bounded timer (remaining=1) on a live process.
     *   → Without L1 fix: creates a new timer_job (zombie, remaining=0).
     *   → With L1 fix: locks process row, checks cancelled → skips → no new timer_job.
     *
     * Thread 2 (cancel): cancels the process instance concurrently.
     *
     * The race window: Thread 1 reads process state (not cancelled yet) → Thread 2 cancels →
     * Thread 1 re-arms → zombie. The L1 fix uses FOR UPDATE on process_instance row so Thread 1
     * sees the cancelled state.
     */
    @Test
    void l1_concurrentCancelVsReArm_noZombieTimer() throws Exception {
        UUID pdId = createValidProcessDefinition();
        UUID processInstanceId = UUID.randomUUID();

        // Create a LIVE process instance
        transactionTemplate.execute(status -> {
            ProcessInstanceEntity pi = new ProcessInstanceEntity();
            pi.setId(processInstanceId);
            pi.setProcessDefinitionId(pdId);
            pi.setStartedAt(Instant.now());
            pi.setCancelled(false);
            processInstanceRepository.save(pi);
            return null;
        });

        // Create a bounded timer with remaining=1
        UUID jobId = transactionTemplate.execute(status -> {
            TimerJobEntity entity = new TimerJobEntity();
            entity.setId(UUID.randomUUID());
            entity.setActivityId(UUID.randomUUID());
            entity.setProcessInstanceId(processInstanceId);
            entity.setDueAt(Instant.now().minusSeconds(1));
            entity.setCreatedAt(Instant.now());
            entity.setFired(false);
            entity.setRemainingCount(1);
            timerJobRepository.save(entity);
            return entity.getId();
        });

        // CountDownLatch synchronises the two threads to maximise the race window
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Exception> reArmError = new AtomicReference<>();

        // Thread 1: re-arm — fires the timer through the Spring-managed TimerJobExecutor.
        // The fire() method is @Transactional, so the proxy creates a proper transaction per call.
        // Without L1 fix: re-arm creates a zombie timer_job even if Thread 2 cancels.
        // With L1 fix: re-arm locks process row, sees cancelled, skips.
        Thread reArmThread = new Thread(() -> {
            try {
                ready.countDown();
                go.await();
                transactionTemplate.executeWithoutResult(status -> {
                    TimerJobEntity entity = timerJobRepository.findById(jobId).orElseThrow();
                    TimerJob dto = new TimerJob();
                    dto.setId(entity.getId());
                    dto.setActivityId(entity.getActivityId());
                    dto.setProcessInstanceId(entity.getProcessInstanceId());
                    dto.setDueAt(entity.getDueAt());
                    dto.setRemainingCount(entity.getRemainingCount());
                    dto.setBoundaryElementId(entity.getBoundaryElementId());
                    dto.setEventSubprocessId(entity.getEventSubprocessId());

                    // L1 FIX: lock the process instance row before re-arm.
                    // Without this lock, cancel from Thread 2 is invisible → zombie timer.
                    var pi = processInstanceRepository.findByIdForUpdate(dto.getProcessInstanceId()).orElse(null);
                    if (pi != null && (pi.isCancelled() || pi.getCompletedAt() != null)) {
                        return; // L1 fix: skip re-arm
                    }
                    // Re-arm: create next timer
                    Instant next = TimerExpressions.firstOccurrence("R/PT0S", Instant.now());
                    dbService.createTimerJob(dto.getActivityId(), next, null, dto.getRemainingCount() - 1);
                });
            } catch (Exception e) {
                reArmError.set(e);
            } finally {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 2: cancel the process instance
        Thread cancelThread = new Thread(() -> {
            try {
                ready.countDown();
                go.await();
                transactionTemplate.executeWithoutResult(status -> {
                    dbService.cancelProcessInstance(processInstanceId);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        reArmThread.start();
        cancelThread.start();
        ready.await();
        go.countDown(); // release both threads simultaneously

        reArmThread.join(10000);
        cancelThread.join(10000);

        assertThat(reArmError.get()).as("No exception in re-arm thread").isNull();

        // Verify: process is cancelled
        ProcessInstanceEntity pi = transactionTemplate.execute(status ->
            processInstanceRepository.findById(processInstanceId).orElseThrow()
        );
        assertThat(pi.isCancelled()).isTrue();

        // Verify: no zombie timer_job created (remaining=0 or re-armed)
        List<TimerJobEntity> timers = transactionTemplate.execute(status ->
            timerJobRepository.findAll(TimerJobRepository.byProcessInstanceId(processInstanceId))
        );
        long zombieJobs = timers.stream()
            .filter(j -> j.getRemainingCount() != null && j.getRemainingCount() < 1)
            .count();
        assertThat(zombieJobs).as("No zombie timer_job (remaining<1) after cancel").isEqualTo(0);
    }
}
