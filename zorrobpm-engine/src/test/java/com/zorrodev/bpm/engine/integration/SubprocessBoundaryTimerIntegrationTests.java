package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
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

/**
 * WO-C8-34 (CR-04): boundary timers are armed on container entry — embedded
 * subprocess (this class). The timer attached to the {@code sub1} container
 * must produce a timer-job row for the container's host activity; before the
 * fix no row existed at all (SubProcessHandler never called BoundaryScheduler).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class SubprocessBoundaryTimerIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void boundaryTimerOnSubprocessContainer_isScheduledOnEntry() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c834-sub-boundary-timer.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        // the container host row exists and stays active (inner user task parked)
        ActivityEntity host = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "sub1".equals(a.getBpmnElementId()))
            .findFirst().orElseThrow();

        // exactly one timer job for the container boundary, bound to the host row
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> host.getId().equals(r.getActivityId()))
            .filter(r -> "subTimeout".equals(r.getBoundaryElementId()))
            .filter(r -> !r.isFired())
            .toList();
        assertThat(rows).as("timer job for the subprocess-container boundary").hasSize(1);
    }
}
