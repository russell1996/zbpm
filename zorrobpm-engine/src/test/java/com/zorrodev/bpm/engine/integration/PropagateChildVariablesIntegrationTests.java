package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-4: the call-activity propagateAllChildVariables flag controls whether child variables flow to the parent. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class PropagateChildVariablesIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private VariableRepository variableRepository;

    private UUID runParent(String parentFixture) throws Exception {
        processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/test-propagate-child.bpmn")));
        ProcessDefinition parent = processDefinitionService.addProcessDefinition(Files.readString(Paths.get(parentFixture)));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(parent.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void propagateFalseKeepsChildVariablesOutOfTheParent() throws Exception {
        // child sets childVar=42; with propagateAllChildVariables="false" the parent must not receive it.
        UUID parentInstanceId = runParent("src/test/files/test-propagate-false.bpmn");

        assertThat(queryService.getProcessInstance(parentInstanceId).getCompletedAt()).isNotNull();
        assertThat(variableRepository.findByNameAndProcessInstanceId("childVar", parentInstanceId)).isEmpty();
    }

    @Transactional
    @Test
    void propagateDefaultCopiesChildVariablesToTheParent() throws Exception {
        // no flag -> default true: the parent receives childVar (backward-compatible behaviour).
        UUID parentInstanceId = runParent("src/test/files/test-propagate-default.bpmn");

        assertThat(queryService.getProcessInstance(parentInstanceId).getCompletedAt()).isNotNull();
        assertThat(variableRepository.findByNameAndProcessInstanceId("childVar", parentInstanceId))
            .get().extracting("textValue").isEqualTo("42");
    }
}
