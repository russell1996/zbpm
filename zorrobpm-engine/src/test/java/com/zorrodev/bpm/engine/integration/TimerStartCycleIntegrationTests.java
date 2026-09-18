package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerStartJobExecutor;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C8-3: a repeating timeCycle timer start (R/<duration> or cron) reschedules its next occurrence after firing.
 *
 * WO-REL-13 (R-03): each step runs in its OWN committed transaction — timer fires (TimerStartJobExecutor
 * REQUIRES_NEW) cannot see rows of the test's outer transaction (READ COMMITTED).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TimerStartCycleIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private TimerStartJobExecutor timerStartJobExecutor;

    @Autowired
    private TimerStartJobRepository timerStartJobRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Runs the action in its own committed transaction (WO-REL-13: fires must see committed rows). */
    private void inNewTx(Runnable action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            action.run();
            return null;
        });
    }

    private TimerStartJobEntity pendingJob(UUID processDefinitionId) {
        return timerStartJobRepository.findAll().stream()
            .filter(j -> j.getProcessDefinitionId().equals(processDefinitionId) && !j.isFired())
            .findFirst().orElseThrow();
    }

    private long instanceCount(UUID processDefinitionId) {
        return processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(processDefinitionId)).count();
    }

    @Test
    void repeatingTimerStartReschedulesAfterFiring() throws Exception {
        // timerStart with timeCycle R/PT0S (unbounded, immediately due). Each firing starts an instance and
        // schedules the next occurrence, so firing the pending job twice yields two instances.
        String bpmn = Files.readString(Paths.get("src/test/files/test-timer-start-cycle.bpmn"));

        UUID[] defId = new UUID[1];
        inNewTx(() -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            defId[0] = model.getId();
        });

        TimerStartJobEntity first = pendingJob(defId[0]);
        inNewTx(() -> timerStartJobExecutor.fire(first.getId(), first.getProcessDefinitionId(), first.getElementId(), first.getDueAt(), first.getRemainingCount()));
        assertThat(instanceCount(defId[0])).isEqualTo(1);

        // firing produced a fresh pending job (the reschedule); fire it too
        TimerStartJobEntity second = pendingJob(defId[0]);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // WO-REL-14: unbounded R/PT0S cycle — remainingCount stays null (infinite) across reschedules.
        assertThat(second.getRemainingCount()).isNull();
        inNewTx(() -> timerStartJobExecutor.fire(second.getId(), second.getProcessDefinitionId(), second.getElementId(), second.getDueAt(), second.getRemainingCount()));
        assertThat(instanceCount(defId[0])).isEqualTo(2);

        // and it keeps repeating: another pending job is queued
        assertThat(timerStartJobRepository.findAll().stream()
            .filter(j -> j.getProcessDefinitionId().equals(defId[0]) && !j.isFired())).isNotEmpty();

        List<ProcessInstanceEntity> instances = processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(defId[0])).toList();
        assertThat(instances).allMatch(pi -> pi.getCompletedAt() != null);
    }
}
