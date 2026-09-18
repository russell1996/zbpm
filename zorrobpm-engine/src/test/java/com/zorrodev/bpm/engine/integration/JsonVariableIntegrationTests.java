package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
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

/** C8-1B: a JSON object variable is read by FEEL — nested property access in an expression and a condition. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class JsonVariableIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Transactional
    @Test
    void jsonObjectVariableIsReadByFeelNestedProperty() throws Exception {
        // order = { total: 150, customer: "acme" } (JSON). readOrder: order.total - 50 = 100 (LONG);
        // gateway: order.total >= 100 -> endHigh. Before JSON support, `order` was an opaque string and
        // `order.total` did not resolve.
        String bpmn = Files.readString(Paths.get("src/test/files/test-json-variable.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable order = new ProcessVariable();
        order.setName("order");
        order.setType(ProcessVariableType.JSON);
        order.setValue("{\"total\":150,\"customer\":\"acme\"}");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(order));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // nested property read inside a FEEL expression
        ProcessVariableEntity net = variableRepository.findByNameAndProcessInstanceId("net", processInstanceId).orElseThrow();
        assertThat(net.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(net.getTextValue()).isEqualTo("100");

        // nested property read inside a gateway condition routed to the high branch
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endHigh") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endLow"));
    }
}
