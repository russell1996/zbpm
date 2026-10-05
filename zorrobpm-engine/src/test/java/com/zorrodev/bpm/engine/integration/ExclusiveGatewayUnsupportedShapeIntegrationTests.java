package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
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
 * WO-SEC-59 #1 introduced the clear IllegalStateException for a degenerate 1-in/1-out
 * exclusive gateway (an incident instead of a silent hang).
 *
 * <p><b>REWRITTEN by WO-C8-35 (CR-10 ч.1)</b>: the incident was the wrong call. With
 * one incoming and one outgoing there is nothing to evaluate and nothing to merge — the
 * gateway is a no-op in the model, so the engine must behave like one. The shape is
 * now an unconditional pass-through, and this test pins THAT instead of the incident.
 * The genuinely unsupported shapes (0/0, 1/2, 2/2 …) still raise the exception; the
 * sibling test below keeps that half of WO-SEC-59 alive so the rewrite is not a
 * silent loss of coverage.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ExclusiveGatewayUnsupportedShapeIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;

    @Transactional
    @Test
    void degenerateExclusiveGateway_oneInOneOut_passesThroughWithoutIncident() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-exclusive-gateway-degenerate.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // pass-through: the single outgoing flow is taken and the instance completes
        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt())
            .as("1-in/1-out gateway is a no-op: the flow goes straight through")
            .isNotNull();

        ActivityEntity gateway = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("xor"))
            .findFirst().orElseThrow();
        assertThat(gateway.getStatus())
            .as("the gateway row exists and completed, like any pass-through element")
            .isEqualTo(com.zorrodev.bpm.engine.entity.ActivityStatus.COMPLETED);

        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> "endEvent".equals(a.getBpmnElementId()))
            .findFirst().orElseThrow().getStatus())
            .as("the end event behind the gateway was reached")
            .isEqualTo(com.zorrodev.bpm.engine.entity.ActivityStatus.COMPLETED);

        assertThat(incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(gateway.getId()))
            .toList())
            .as("no incident for a legal (if pointless) diagram")
            .isEmpty();
    }

    /**
     * WO-SEC-59 coverage that WO-C8-35 must NOT lose: a shape with no routing
     * meaning (two incomings AND two outgoings — neither a split nor a merge) still
     * raises the exception the engine records as an incident, instead of guessing.
     */
    @Transactional
    @Test
    void exclusiveGateway_twoInTwoOut_stillRaisesIncidentInsteadOfGuessing() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-exclusive-gateway-ambiguous-shape.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // the fork's tasks park first — the gateway is only entered when a branch
        // delivers into it, so the test has to complete them
        UserTaskQuery q = new UserTaskQuery();
        q.setProcessInstanceId(processInstanceId);
        for (UserTask t : queryService.findUserTasks(q, null).getData()) {
            runtimeService.completeUserTask(t.getId(), List.of());
        }

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNull();

        ActivityEntity gateway = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("xor2"))
            .findFirst().orElseThrow();

        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(gateway.getId()))
            .toList();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("unsupported incoming/outgoing shape")
            .doesNotContain("NullPointer");
    }
}
