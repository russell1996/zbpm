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
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-3: a repeating timeCycle timer start (R/<duration> or cron) reschedules its next occurrence after firing. */
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

    private TimerStartJobEntity pendingJob(UUID processDefinitionId) {
        return timerStartJobRepository.findAll().stream()
            .filter(j -> j.getProcessDefinitionId().equals(processDefinitionId) && !j.isFired())
            .findFirst().orElseThrow();
    }

    private long instanceCount(UUID processDefinitionId) {
        return processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(processDefinitionId)).count();
    }

    @Transactional
    @Test
    void repeatingTimerStartReschedulesAfterFiring() throws Exception {
        // timerStart with timeCycle R/PT0S (unbounded, immediately due). Each firing starts an instance and
        // schedules the next occurrence, so firing the pending job twice yields two instances.
        String bpmn = Files.readString(Paths.get("src/test/files/test-timer-start-cycle.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        UUID defId = model.getId();

        TimerStartJobEntity first = pendingJob(defId);
        timerStartJobExecutor.fire(first.getId(), first.getProcessDefinitionId(), first.getElementId());
        assertThat(instanceCount(defId)).isEqualTo(1);

        // firing produced a fresh pending job (the reschedule); fire it too
        TimerStartJobEntity second = pendingJob(defId);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        timerStartJobExecutor.fire(second.getId(), second.getProcessDefinitionId(), second.getElementId());
        assertThat(instanceCount(defId)).isEqualTo(2);

        // and it keeps repeating: another pending job is queued
        assertThat(timerStartJobRepository.findAll().stream()
            .filter(j -> j.getProcessDefinitionId().equals(defId) && !j.isFired())).isNotEmpty();

        List<ProcessInstanceEntity> instances = processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(defId)).toList();
        assertThat(instances).allMatch(pi -> pi.getCompletedAt() != null);
    }
}
