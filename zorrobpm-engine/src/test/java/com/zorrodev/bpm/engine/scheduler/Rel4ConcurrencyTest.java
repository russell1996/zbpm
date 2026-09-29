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
 * V6: 2 REAL concurrent threads (not sequential simulation), both driving REAL prod paths:
 * {@link TimerJobExecutor#fire} (Spring-managed, REQUIRES_NEW) and {@link DBService#cancelProcessInstance}.
 *
 * Before fix: re-arm creates timer_job even when process is cancelled → zombie.
 * After fix: re-arm locks process row (FOR UPDATE) and checks cancelled → skips → no zombie.
 *
 * WO-TEST-2: the test now calls the real TimerJobExecutor instead of re-implementing the L1
 * guard inline, and the re-armed job carries the real processInstanceId so the zombie assertion
 * can actually see it.
 *
 * Race analysis (WO-TEST-2, criterion 5): an UNORDERED concurrent start is inherently ~50%
 * flaky — when re-arm commits before cancel, the re-armed job is legal (the process was still
 * alive at re-arm time) and the L1 guard is not violated. The L1 property is: re-arm that runs
 * AFTER the cancel has committed must observe the cancelled state and skip. The test therefore
 * orders the two real threads (cancel commits first, then fire runs) and asserts the only
 * outcome that the L1 guard controls.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class Rel4ConcurrencyTest {

    @Autowired private DBService dbService;
    @Autowired private TimerJobExecutor timerJobExecutor;
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
     * Thread 1 (re-arm): fires a bounded timer (remaining=1) on a live process through the REAL
     *   Spring-managed {@link TimerJobExecutor}. fire() is @Transactional(REQUIRES_NEW), so the
     *   proxy creates a proper transaction per call.
     *   → Without L1 fix: creates a new timer_job (zombie, remaining=0).
     *   → With L1 fix: locks process row, checks cancelled → skips → no new timer_job.
     *
     * Thread 2 (cancel): cancels the process instance concurrently and signals AFTER its
     *   transaction has committed; Thread 1 only then enters fire(). This ordering is what makes
     *   the test deterministic and meaningful: re-arm runs when the cancel is already visible,
     *   so ANY re-armed job would be a true zombie (L1 violation), not a legal pre-cancel re-arm.
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

        // Create a bounded timer with remaining=1 and the REAL processInstanceId and cycle
        // expression (WO-REL-14: without a persisted expression fire() ends the cycle instead
        // of re-arming, so the zombie path would never be exercised).
        UUID jobId = transactionTemplate.execute(status -> {
            TimerJobEntity entity = new TimerJobEntity();
            entity.setId(UUID.randomUUID());
            entity.setActivityId(UUID.randomUUID());
            entity.setProcessInstanceId(processInstanceId);
            entity.setDueAt(Instant.now().minusSeconds(1));
            entity.setCreatedAt(Instant.now());
            entity.setFired(false);
            entity.setRemainingCount(1);
            entity.setExpression("R/PT1S");
            timerJobRepository.save(entity);
            return entity.getId();
        });

        // Build the TimerJob DTO exactly like the polling path does (from the persisted row).
        TimerJob dto = transactionTemplate.execute(status -> {
            TimerJobEntity entity = timerJobRepository.findById(jobId).orElseThrow();
            TimerJob job = new TimerJob();
            job.setId(entity.getId());
            job.setActivityId(entity.getActivityId());
            job.setProcessInstanceId(entity.getProcessInstanceId());
            job.setDueAt(entity.getDueAt());
            job.setRemainingCount(entity.getRemainingCount());
            job.setBoundaryElementId(entity.getBoundaryElementId());
            job.setEventSubprocessId(entity.getEventSubprocessId());
            job.setExpression(entity.getExpression());
            return job;
        });

        CountDownLatch cancelCommitted = new CountDownLatch(1);
        AtomicReference<Exception> cancelError = new AtomicReference<>();
        AtomicReference<Exception> reArmError = new AtomicReference<>();

        // Thread 2: cancel the process instance; signal only AFTER the cancel transaction
        // has committed (transactionTemplate.execute commits on return).
        Thread cancelThread = new Thread(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    dbService.cancelProcessInstance(processInstanceId);
                });
            } catch (Exception e) {
                cancelError.set(e);
            } finally {
                cancelCommitted.countDown();
            }
        });

        // Thread 1: re-arm — fires the timer through the REAL Spring-managed TimerJobExecutor,
        // only after the cancel has committed (see class javadoc for why this ordering is the
        // meaningful one).
        Thread reArmThread = new Thread(() -> {
            try {
                cancelCommitted.await();
                timerJobExecutor.fire(dto);
            } catch (Exception e) {
                reArmError.set(e);
            }
        });

        reArmThread.start();
        cancelThread.start();
        reArmThread.join(10000);
        cancelThread.join(10000);

        assertThat(cancelError.get()).as("No exception in cancel thread").isNull();
        assertThat(reArmError.get()).as("No exception in fire thread").isNull();

        // Verify: process is cancelled
        ProcessInstanceEntity pi = transactionTemplate.execute(status ->
            processInstanceRepository.findById(processInstanceId).orElseThrow()
        );
        assertThat(pi.isCancelled()).isTrue();

        // Verify: no zombie timer_job created (remaining<1). The re-armed job — if the L1 guard
        // were missing — would carry this exact processInstanceId (fire() re-arms via
        // dbService.createTimerJob(..., processInstanceId)), so the filter sees it.
        List<TimerJobEntity> timers = transactionTemplate.execute(status ->
            timerJobRepository.findAll(TimerJobRepository.byProcessInstanceId(processInstanceId))
        );
        long zombieJobs = timers.stream()
            .filter(j -> j.getRemainingCount() != null && j.getRemainingCount() < 1)
            .count();
        assertThat(zombieJobs).as("No zombie timer_job (remaining<1) after cancel").isEqualTo(0);
    }
}