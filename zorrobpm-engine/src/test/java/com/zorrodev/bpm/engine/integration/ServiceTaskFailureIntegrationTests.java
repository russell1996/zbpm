package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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

/**
 * A worker failure decrements the service task's retries; an incident is raised only when retries are
 * exhausted, carrying the worker's error message; resolving it re-dispatches the job, which can then succeed.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ServiceTaskFailureIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    private ActivityEntity activeServiceTask(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
    }

    private List<IncidentEntity> incidentsFor(UUID activityId) {
        return incidentRepository.findAll().stream().filter(i -> i.getActivityId().equals(activityId)).toList();
    }

    @Transactional
    @Test
    void workerFailureExhaustsRetriesThenRaisesIncidentResolvableByRetry() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn")); // retries=2
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        UUID svc1 = activeServiceTask(pi).getId();

        // attempt 1 fails -> retries 2->1, no incident yet
        runtimeService.failServiceTask(svc1, "connect timed out", null);
        assertThat(incidentsFor(svc1)).isEmpty();

        // attempt 2 fails -> retries 1->0 -> incident carrying the worker's message; instance stays parked
        runtimeService.failServiceTask(svc1, "downstream 503", null);
        List<IncidentEntity> incidents = incidentsFor(svc1);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).isEqualTo("downstream 503");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();

        // resolve -> re-dispatches the job as a fresh service-task instance; complete it -> process finishes
        runtimeService.resolveIncident(incidents.get(0).getId(), List.of());
        UUID svc2 = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && !a.getId().equals(svc1))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();

        runtimeService.completeServiceTask(svc2, List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)).isTrue();
    }

    @Transactional
    @Test
    void failWithRetriesZeroRaisesIncidentOnFirstCall() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn")); // retries=2
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        UUID svc = activeServiceTask(pi).getId();

        // explicit retries=0 (Camunda failJob) -> incident immediately, regardless of the 2-retry budget
        runtimeService.failServiceTask(svc, "fatal: bad config", 0);

        List<IncidentEntity> incidents = incidentsFor(svc);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).isEqualTo("fatal: bad config");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }
}
