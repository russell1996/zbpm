package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
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

/**
 * WO-ENG-11: Call Activity variable propagation.
 * <p>Input side: {@code propagateAllParentVariables="false"} must stop the blanket parent-variable
 * copy — without Input mappings the child starts empty, with mappings only the mapped variables are
 * seeded. Default (flag absent) keeps copying everything.</p>
 * <p>Output side: explicit Output mappings take precedence over {@code propagateAllChildVariables}
 * (only mapped variables reach the parent, regardless of the flag); without mappings the historical
 * toggle behaviour is unchanged (covered by {@link PropagateChildVariablesIntegrationTests}).</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CallActivityVariablePropagationIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private VariableRepository variableRepository;

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    /** Deploys the shared child and the given parent fixture, starts the parent with two marker vars. */
    private UUID runParent(String parentFixture) throws Exception {
        processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/test-eng11-child.bpmn")));
        ProcessDefinition parent = processDefinitionService.addProcessDefinition(Files.readString(Paths.get(parentFixture)));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(parent.getId());
        dto.setVariables(List.of(
            var("parentVar", ProcessVariableType.LONG, "7"),
            var("otherVar", ProcessVariableType.LONG, "9")
        ));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UUID childInstanceId(UUID parentInstanceId) {
        List<com.zorrodev.bpm.engine.entity.ProcessInstanceEntity> children = processInstanceRepository
            .findAll(ProcessInstanceRepository.byParentProcessInstanceId(parentInstanceId));
        assertThat(children).hasSize(1);
        return children.get(0).getId();
    }

    @Transactional
    @Test
    void criterion1_propagateFalseWithoutInputMappings_childStartsWithoutParentVariables() throws Exception {
        UUID parentId = runParent("src/test/files/test-eng11-parent-no-propagate.bpmn");
        UUID childId = childInstanceId(parentId);

        // the whole flow completed (child ran to its end event)
        assertThat(queryService.getProcessInstance(childId).getCompletedAt()).isNotNull();

        // none of the parent's variables leaked into the child instance
        assertThat(variableRepository.findByNameAndProcessInstanceId("parentVar", childId)).isEmpty();
        assertThat(variableRepository.findByNameAndProcessInstanceId("otherVar", childId)).isEmpty();
        // ...and the child's own variable still exists (proves the child actually executed)
        assertThat(variableRepository.findByNameAndProcessInstanceId("childVar", childId))
            .get().extracting("textValue").isEqualTo("42");
    }

    @Transactional
    @Test
    void criterion2_propagateFalseWithInputMappings_onlyMappedVariablesSeedTheChild() throws Exception {
        UUID parentId = runParent("src/test/files/test-eng11-parent-input-mappings.bpmn");
        UUID childId = childInstanceId(parentId);

        assertThat(queryService.getProcessInstance(childId).getCompletedAt()).isNotNull();

        // only the Input-mapped variable is present in the child...
        assertThat(variableRepository.findByNameAndProcessInstanceId("mappedVar", childId))
            .get().extracting("textValue").isEqualTo("7");
        // ...every other parent variable stayed out
        assertThat(variableRepository.findByNameAndProcessInstanceId("parentVar", childId)).isEmpty();
        assertThat(variableRepository.findByNameAndProcessInstanceId("otherVar", childId)).isEmpty();
    }

    @Transactional
    @Test
    void criterion3_flagAbsent_allParentVariablesAreStillCopied() throws Exception {
        UUID parentId = runParent("src/test/files/test-eng11-parent-default.bpmn");
        UUID childId = childInstanceId(parentId);

        assertThat(queryService.getProcessInstance(childId).getCompletedAt()).isNotNull();

        // default (flag absent → true): both parent variables are visible in the child
        assertThat(variableRepository.findByNameAndProcessInstanceId("parentVar", childId))
            .get().extracting("textValue").isEqualTo("7");
        assertThat(variableRepository.findByNameAndProcessInstanceId("otherVar", childId))
            .get().extracting("textValue").isEqualTo("9");
    }

    @Transactional
    @Test
    void criterion4a_outputMappingsPresent_onlyMappedVariablesReachTheParent_defaultFlag() throws Exception {
        UUID parentId = runParent("src/test/files/test-eng11-parent-output-mappings.bpmn");

        assertThat(queryService.getProcessInstance(parentId).getCompletedAt()).isNotNull();

        // only the Output-mapped pickedVar reaches the parent, not the raw childVar
        assertThat(variableRepository.findByNameAndProcessInstanceId("pickedVar", parentId))
            .get().extracting("textValue").isEqualTo("42");
        assertThat(variableRepository.findByNameAndProcessInstanceId("childVar", parentId)).isEmpty();
    }

    @Transactional
    @Test
    void criterion4b_outputMappingsPresent_onlyMappedVariablesReachTheParent_flagFalse() throws Exception {
        // propagateAllChildVariables="false" does NOT suppress explicit Output mappings
        UUID parentId = runParent("src/test/files/test-eng11-parent-output-mappings-false.bpmn");

        assertThat(queryService.getProcessInstance(parentId).getCompletedAt()).isNotNull();

        assertThat(variableRepository.findByNameAndProcessInstanceId("pickedVar", parentId))
            .get().extracting("textValue").isEqualTo("42");
        assertThat(variableRepository.findByNameAndProcessInstanceId("childVar", parentId)).isEmpty();
    }
}
