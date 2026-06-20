package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ActivityService;
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
public class MessageCorrelationKeyIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityService activityService;

    private ProcessVariable orderId(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("orderId");
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private UUID start(UUID definitionId, String orderId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        dto.setVariables(List.of(orderId(orderId)));
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void messageCorrelatesByKeyToTheRightInstanceAmongNameCollisions() throws Exception {
        // two instances of the same definition both wait on the message name "orderUpdate"; the
        // correlation key (= orderId) decides which one a published message reaches.
        String bpmn = Files.readString(Paths.get("src/test/files/test-message-correlation-key.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID instanceA = start(model.getId(), "A");
        UUID instanceB = start(model.getId(), "B");

        // both are parked at the message catch
        assertThat(queryService.getProcessInstance(instanceA).getCompletedAt()).isNull();
        assertThat(queryService.getProcessInstance(instanceB).getCompletedAt()).isNull();

        // correlate by key "B" -> only instance B resumes and completes; A stays parked
        activityService.correlateMessage("orderUpdate", "B", null, List.of());
        assertThat(queryService.getProcessInstance(instanceB).getCompletedAt()).isNotNull();
        assertThat(queryService.getProcessInstance(instanceA).getCompletedAt()).isNull();

        // correlate by key "A" -> instance A now completes too
        activityService.correlateMessage("orderUpdate", "A", null, List.of());
        assertThat(queryService.getProcessInstance(instanceA).getCompletedAt()).isNotNull();
    }

    @Transactional
    @Test
    void correlateByNonMatchingKeyWakesNoInstance() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-message-correlation-key.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID instanceA = start(model.getId(), "A");

        // a key that matches no subscription leaves the waiting instance untouched
        activityService.correlateMessage("orderUpdate", "Z", null, List.of());
        assertThat(queryService.getProcessInstance(instanceA).getCompletedAt()).isNull();
    }
}
