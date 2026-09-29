package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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

/** C8-1C: a FEEL expression that returns a structure (object/list) is stored as JSON and can be read back. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FeelObjectResultIntegrationTests {

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
    void feelObjectResultIsStoredAsJsonAndReadBack() throws Exception {
        // build: a FEEL context {id, total, items:[...]} -> stored as JSON 'order'; the gateway then reads
        // order.total >= 100 back from that JSON. Before this, the Scala FEEL result became an opaque string.
        String bpmn = Files.readString(Paths.get("src/test/files/test-feel-object-result.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // the FEEL object was serialized to JSON
        ProcessVariableEntity order = variableRepository.findByNameAndProcessInstanceId("order", processInstanceId).orElseThrow();
        assertThat(order.getType()).isEqualTo(ProcessVariableType.JSON);
        assertThat(order.getTextValue()).contains("\"total\":100").contains("\"items\":[10,20]");

        // and reading order.total back from JSON routed the gateway to the high branch
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endHigh") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endLow"));
    }
}
