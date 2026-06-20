package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DmnService;
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
public class BusinessRuleTaskIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private DmnService dmnService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private VariableRepository variableRepository;

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    @Transactional
    @Test
    void businessRuleTaskEvaluatesDeployedDmnDecision() throws Exception {
        // a Camunda-8-style DMN decision table (DMN 1.3 + FEEL) deployed by id; the business rule task
        // references it via zeebe:calledDecision and stores the output in a variable.
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-discount.dmn")));
        String bpmn = Files.readString(Paths.get("src/test/files/test-business-rule-dmn.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("category", ProcessVariableType.STRING, "gold")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        // category "gold" -> discount 20 (rule_gold; FIRST hit policy)
        ProcessVariableEntity discount = variableRepository.findByNameAndProcessInstanceId("discount", processInstanceId).orElseThrow();
        assertThat(discount.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(discount.getTextValue()).isEqualTo("20");
    }

    @Transactional
    @Test
    void businessRuleTaskEvaluatesInlineFeelExpression() throws Exception {
        // the FEEL variant: no DMN, an inline expression on the business rule task.
        String bpmn = Files.readString(Paths.get("src/test/files/test-business-rule-feel.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("amount", ProcessVariableType.LONG, "5")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        ProcessVariableEntity doubled = variableRepository.findByNameAndProcessInstanceId("doubled", processInstanceId).orElseThrow();
        assertThat(doubled.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(doubled.getTextValue()).isEqualTo("10");
    }
}
