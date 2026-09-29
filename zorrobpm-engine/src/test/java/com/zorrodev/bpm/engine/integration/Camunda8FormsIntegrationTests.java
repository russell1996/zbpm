package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
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

/** Camunda 8 task forms: job-worker send task ({@code zeebe:taskDefinition}) and {@code zeebe:userTask}. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Camunda8FormsIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ActivityEntity active(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
    }

    @Transactional
    @Test
    void sendTaskWithZeebeTaskDefinitionRunsAsAJobWorker() throws Exception {
        // a Camunda 8 send task carries a zeebe:taskDefinition and behaves like a service task: it parks as
        // a job until completed (rather than passing through as a message throw).
        String bpmn = Files.readString(Paths.get("src/test/files/test-send-task-zeebe.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // parked as a job (not passed through)
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        ActivityEntity send = active(processInstanceId, "send");

        runtimeService.completeServiceTask(send.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)).isTrue();
    }

    @Transactional
    @Test
    void scriptTaskWithZeebeTaskDefinitionRunsAsAJobWorker() throws Exception {
        // a Camunda 8 script task can carry a zeebe:taskDefinition (job worker) instead of an inline FEEL
        // script: it parks as a job until completed, like a service task.
        String bpmn = Files.readString(Paths.get("src/test/files/test-script-task-job.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        ActivityEntity scriptJob = active(processInstanceId, "scriptJob");

        runtimeService.completeServiceTask(scriptJob.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)).isTrue();
    }

    @Transactional
    @Test
    void userTaskWithZeebeUserTaskMarkerParksAndCompletes() throws Exception {
        // a Camunda 8 native user task (zeebe:userTask marker) parses and runs like a user task.
        String bpmn = Files.readString(Paths.get("src/test/files/test-user-task-zeebe.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity review = active(processInstanceId, "review");
        runtimeService.completeUserTask(review.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)).isTrue();
    }
}
