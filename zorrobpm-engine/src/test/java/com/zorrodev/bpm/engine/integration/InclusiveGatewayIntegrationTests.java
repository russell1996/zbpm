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
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
public class InclusiveGatewayIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ProcessVariable var(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private List<ActivityEntity> run(List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-inclusive-gateway.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    @Transactional
    @Test
    void splitTakesAllTrueBranchesAndJoinWaitsForExactlyThose() throws Exception {
        // a=yes, b=yes -> two branches activated; the third (default) is not. The join fires once both
        // activated branches arrive (it must not wait for the un-taken default flow), and the process ends.
        List<ActivityEntity> activities = run(List.of(var("a", "yes"), var("b", "yes")));

        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("flowA") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("flowB") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("flowDefault"));
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("join") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void splitFallsBackToDefaultWhenNoConditionMatches() throws Exception {
        // a=no, b=no -> no condition is true, so the default flow is taken; the join waits for that one
        // branch and the process ends.
        List<ActivityEntity> activities = run(List.of(var("a", "no"), var("b", "no")));

        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("flowDefault") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("flowA"));
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("flowB"));
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("join") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
