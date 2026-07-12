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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-4: L1 — bounded timer re-arm respects cancelled process.
 *
 * Before fix: re-arm creates timer_job even when process is cancelled → zombie.
 * After fix: re-arm checks cancelled/completed → skips → no zombie.
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
     * Proof-of-failure: cancelled process should NOT get re-armed timer_job.
     * Before fix: re-arm creates new timer_job → zombie (remaining decreases).
     * After fix: re-arm checks cancelled → skips → remaining stays same.
     */
    @Test
    void l1_proofOfFailure_reArmSkipsCancelledProcess() throws Exception {
        UUID pdId = createValidProcessDefinition();
        UUID processInstanceId = UUID.randomUUID();

        // Create process instance as CANCELLED
        transactionTemplate.execute(status -> {
            ProcessInstanceEntity pi = new ProcessInstanceEntity();
            pi.setId(processInstanceId);
            pi.setProcessDefinitionId(pdId);
            pi.setStartedAt(Instant.now());
            pi.setCancelled(true);
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

        // Fire the timer — should skip re-arm because process is cancelled
        transactionTemplate.execute(status -> {
            TimerJobEntity entity = timerJobRepository.findById(jobId).orElseThrow();
            TimerJob dto = new TimerJob();
            dto.setId(entity.getId());
            dto.setActivityId(entity.getActivityId());
            dto.setProcessInstanceId(entity.getProcessInstanceId());
            dto.setDueAt(entity.getDueAt());
            dto.setRemainingCount(entity.getRemainingCount());
            dto.setBoundaryElementId(entity.getBoundaryElementId());
            dto.setEventSubprocessId(entity.getEventSubprocessId());

            TimerJobExecutor executorBean = new TimerJobExecutor(dbService, null, processInstanceRepository);
            executorBean.fire(dto);
            return null;
        });

        // Verify: no new timer_job was created (re-arm was skipped)
        List<TimerJobEntity> timers = transactionTemplate.execute(status ->
            timerJobRepository.findAll(TimerJobRepository.byProcessInstanceId(processInstanceId)));
        long newJobs = timers.stream()
            .filter(j -> j.getRemainingCount() != null && j.getRemainingCount() < 1)
            .count();
        assertThat(newJobs).isEqualTo(0);
    }
}
