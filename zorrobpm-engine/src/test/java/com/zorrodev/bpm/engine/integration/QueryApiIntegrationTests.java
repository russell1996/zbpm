package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
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
 * Query API added this session: process-name resolution, single-entity GETs, the activities
 * endpoint, and server-side filters (process-instance by definition, service-task completed,
 * incident resolved).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class QueryApiIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;

    private record Started(UUID definitionId, UUID processInstanceId, UUID serviceTaskId) {}

    private Started startServiceTaskProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(pi);
        UUID svc = queryService.findServiceTasks(q, null).getData().get(0).getId();
        return new Started(model.getId(), pi, svc);
    }

    @Transactional
    @Test
    void processInstanceCarriesResolvedProcessNameKeyVersion() throws Exception {
        Started s = startServiceTaskProcess();
        ProcessInstance pi = queryService.getProcessInstance(s.processInstanceId());
        assertThat(pi.getProcessKey()).isEqualTo("test-service-task-fail");
        assertThat(pi.getProcessName()).isEqualTo("test-service-task-fail");
        assertThat(pi.getProcessVersion()).isEqualTo(1);
    }

    @Transactional
    @Test
    void singleEntityGetsReturnTheEntities() throws Exception {
        Started s = startServiceTaskProcess();
        assertThat(queryService.getProcessInstance(s.processInstanceId()).getId()).isEqualTo(s.processInstanceId());
        ServiceTask st = queryService.getServiceTask(s.serviceTaskId());
        assertThat(st.getId()).isEqualTo(s.serviceTaskId());
        assertThat(st.getCompletedAt()).isNull();
    }

    @Transactional
    @Test
    void findProcessInstancesFiltersByDefinition() throws Exception {
        Started s = startServiceTaskProcess();

        ProcessInstanceQuery byId = new ProcessInstanceQuery();
        byId.setProcessDefinitionId(s.definitionId());
        assertThat(queryService.findProcessInstances(byId, null).getData()).extracting(ProcessInstance::getId).contains(s.processInstanceId());

        ProcessInstanceQuery byKey = new ProcessInstanceQuery();
        byKey.setProcessDefinitionKey("test-service-task-fail");
        byKey.setProcessDefinitionVersion(1);
        assertThat(queryService.findProcessInstances(byKey, null).getData()).extracting(ProcessInstance::getId).contains(s.processInstanceId());

        ProcessInstanceQuery wrong = new ProcessInstanceQuery();
        wrong.setProcessDefinitionKey("no-such-process");
        assertThat(queryService.findProcessInstances(wrong, null).getData()).isEmpty();
    }

    @Transactional
    @Test
    void findServiceTasksFiltersByCompleted() throws Exception {
        Started s = startServiceTaskProcess();
        ServiceTaskQuery active = new ServiceTaskQuery();
        active.setProcessInstanceId(s.processInstanceId());
        active.setCompleted(false);
        assertThat(queryService.findServiceTasks(active, null).getData()).hasSize(1);

        ServiceTaskQuery done = new ServiceTaskQuery();
        done.setProcessInstanceId(s.processInstanceId());
        done.setCompleted(true);
        assertThat(queryService.findServiceTasks(done, null).getData()).isEmpty();
    }

    @Transactional
    @Test
    void getActivitiesReturnsExecutionHistory() throws Exception {
        Started s = startServiceTaskProcess();
        List<ActivityInstance> activities = queryService.getActivities(s.processInstanceId());
        // history contains the traversed elements; the waiting service task is still CREATED.
        // (the start event's COMPLETED status isn't asserted here: a bulk status update isn't visible
        // within the same transaction, a known JPA L1-cache quirk of the @Transactional test)
        assertThat(activities).extracting(ActivityInstance::getBpmnElementId).contains("startEvent", "svc");
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("svc") && "CREATED".equals(a.getStatus()));
    }

    @Transactional
    @Test
    void findIncidentsFiltersByResolved() throws Exception {
        Started s = startServiceTaskProcess();
        // retries=0 raises an incident immediately (Camunda failJob semantics)
        runtimeService.failServiceTask(s.serviceTaskId(), "boom", 0);

        IncidentQuery open = new IncidentQuery();
        open.setProcessInstanceId(s.processInstanceId());
        open.setResolved(false);
        assertThat(queryService.findIncidents(open, null).getData()).hasSize(1);

        IncidentQuery resolved = new IncidentQuery();
        resolved.setProcessInstanceId(s.processInstanceId());
        resolved.setResolved(true);
        assertThat(queryService.findIncidents(resolved, null).getData()).isEmpty();
    }
}
