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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-1A: decimal (DOUBLE) variables round-trip through FEEL — input, computed result, and a gateway condition. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class DoubleVariableIntegrationTests {

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
    void decimalVariableComputesAndRoutesThroughFeel() throws Exception {
        // price (DOUBLE 100.50) -> computeTax: price * 0.2 = 20.1 (stored DOUBLE) -> gateway tax >= 20 -> endHigh.
        // Before DOUBLE support, price entered FEEL as the string "100.50" and the arithmetic/comparison failed.
        String bpmn = Files.readString(Paths.get("src/test/files/test-double-variable.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable price = new ProcessVariable();
        price.setName("price");
        price.setType(ProcessVariableType.DOUBLE);
        price.setValue("100.50");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(price));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // the computed tax is a DOUBLE equal to 20.1 (tolerant of trailing zeros)
        ProcessVariableEntity tax = variableRepository.findByNameAndProcessInstanceId("tax", processInstanceId).orElseThrow();
        assertThat(tax.getType()).isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(new BigDecimal(tax.getTextValue())).isEqualByComparingTo(new BigDecimal("20.1"));

        // the decimal comparison routed to the high branch (not the default low branch)
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endHigh") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endLow"));
    }
}
