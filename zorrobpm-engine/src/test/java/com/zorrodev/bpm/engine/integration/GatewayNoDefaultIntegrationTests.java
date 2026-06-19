package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class GatewayNoDefaultIntegrationTests {

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

    private ProcessVariable action(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("action");
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    @Transactional
    @Test
    void exclusiveGatewayWithNoMatchAndNoDefaultRaisesIncidentInsteadOfNpe() throws Exception {
        // gateway "xor" has two conditional flows (action="A"/"B") and NO default attribute (so its
        // parsed extensions are null). With action="C" nothing matches: the engine must raise a clear
        // incident, not dereference the null extensions (the prior NullPointerException).
        String bpmn = Files.readString(Paths.get("src/test/files/test-gateway-no-default.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(action("C")));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // instance is parked (not completed) with an incident on the gateway
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        ActivityEntity gateway = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("xor"))
            .findFirst().orElseThrow();

        // a clear incident is recorded on the gateway (instead of a NullPointerException)
        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(gateway.getId()))
            .toList();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("no default flow")
            .doesNotContain("NullPointerException");
    }

    @Transactional
    @Test
    void exclusiveGatewayWithMatchingConditionRoutesNormally() throws Exception {
        // regression guard: a matching condition still routes correctly even though the gateway has
        // no default flow / null extensions.
        String bpmn = Files.readString(Paths.get("src/test/files/test-gateway-no-default.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(action("A")));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endA") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endB"));
        // the gateway is a pass-through and must be marked COMPLETED once it routes, not left CREATED
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("xor") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
