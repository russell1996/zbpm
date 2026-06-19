package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TimerStartIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private TimerStartJobExecutor timerStartJobExecutor;

    @Autowired
    private TimerStartJobRepository timerStartJobRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void deployRegistersTimerStartJobAndFiringStartsInstance() throws Exception {
        // timerStart (PT5M) -> endEvent. Deploy registers a timer start job; firing it starts and
        // runs a new instance.
        String bpmn = Files.readString(Paths.get("src/test/files/test-timer-start.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        List<TimerStartJobEntity> jobs = timerStartJobRepository.findAll().stream()
            .filter(j -> j.getProcessDefinitionId().equals(model.getId())).toList();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getElementId()).isEqualTo("timerStart");

        TimerStartJobEntity job = jobs.get(0);
        timerStartJobExecutor.fire(job.getId(), job.getProcessDefinitionId(), job.getElementId());

        List<ProcessInstanceEntity> instances = processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(model.getId())).toList();
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getCompletedAt()).isNotNull();
        assertThat(timerStartJobRepository.findById(job.getId()).orElseThrow().isFired()).isTrue();

        var activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(instances.get(0).getId())).toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("timerStart") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
