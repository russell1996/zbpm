package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IoMappingIntegrationTests {

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

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private ProcessVariableEntity variable(UUID processInstanceId, String name) {
        return variableRepository.findByNameAndProcessInstanceId(name, processInstanceId).orElseThrow();
    }

    @Transactional
    @Test
    void inputMappingAppliedOnActivationAndOutputMappingOnCompletion() throws Exception {
        // the user task has an input mapping (taskOrder = orderId) applied on activation and an output
        // mapping (decision = approved) applied when it completes.
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("orderId", ProcessVariableType.STRING, "A-1")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // input mapping has run on activation: taskOrder == orderId
        assertThat(variable(processInstanceId, "taskOrder").getTextValue()).isEqualTo("A-1");

        ActivityEntity review = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("review") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(review.getId(), List.of(var("approved", ProcessVariableType.BOOLEAN, "true")));

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        // output mapping has run on completion: decision == approved
        ProcessVariableEntity decision = variable(processInstanceId, "decision");
        assertThat(decision.getType()).isEqualTo(ProcessVariableType.BOOLEAN);
        assertThat(decision.getTextValue()).isEqualTo("true");
    }

    @Transactional
    @Test
    void inputMappingLocalVariableDoesNotLeakToTheInstance() throws Exception {
        // Camunda 8 scoping: the input-mapped "taskOrder" is local to the task; after the task completes it
        // is dropped, while the output-mapped "decision" propagates to the instance.
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("orderId", ProcessVariableType.STRING, "A-1")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity review = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("review") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(review.getId(), List.of(var("approved", ProcessVariableType.BOOLEAN, "true")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // the input-local variable is gone (it never became an instance variable)...
        assertThat(variableRepository.findByNameAndProcessInstanceId("taskOrder", processInstanceId)).isEmpty();
        // ...while the output-mapped variable did propagate to the instance
        assertThat(variableRepository.findByNameAndProcessInstanceId("decision", processInstanceId)).isPresent();
    }
}
