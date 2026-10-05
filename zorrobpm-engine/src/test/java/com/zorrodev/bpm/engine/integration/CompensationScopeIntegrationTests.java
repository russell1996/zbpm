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

/**
 * WO-C8-34 (CR-07): compensation throw inside a subprocess compensates ONLY
 * its own scope — the completed OUTER work (same instance, outside the scope)
 * must NOT be compensated. Before the fix candidates were instance-wide, so
 * the outer handler ran too (log would end with 9 instead of 1).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CompensationScopeIntegrationTests {

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
    void compensationThrowInSubprocess_compensatesOnlyOwnScope() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c834-comp-scope.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable log = new ProcessVariable();
        log.setName("log");
        log.setType(ProcessVariableType.LONG);
        log.setValue("0");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(log));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        // only the INNER handler ran (log 0 -> 1); the outer handler would
        // have made it 9 (outer only) or 19/91 (both, either order).
        ProcessVariableEntity result = variableRepository.findByNameAndProcessInstanceId("log", processInstanceId).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1");

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> "innerHandler".equals(a.getBpmnElementId())
            && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> "outerHandler".equals(a.getBpmnElementId()));
    }
}
