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
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
public class ScriptTaskIntegrationTests {

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

    @Autowired
    private IncidentRepository incidentRepository;

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
    void scriptTasksEvaluateFeelAndWriteTypedResultVariables() throws Exception {
        // three script tasks compute a numeric (LONG), a boolean and a string result from the same
        // instance variables and store each into its configured result variable.
        String bpmn = Files.readString(Paths.get("src/test/files/test-script-task.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(
            var("a", ProcessVariableType.LONG, "5"),
            var("b", ProcessVariableType.LONG, "3"),
            var("name", ProcessVariableType.STRING, "hi")));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        // a + b = 8 stored as a LONG; a > b = true stored as a BOOLEAN; upper case(name) = "HI" as STRING
        ProcessVariableEntity sum = variable(processInstanceId, "sum");
        assertThat(sum.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(sum.getTextValue()).isEqualTo("8");

        ProcessVariableEntity greater = variable(processInstanceId, "greater");
        assertThat(greater.getType()).isEqualTo(ProcessVariableType.BOOLEAN);
        assertThat(greater.getTextValue()).isEqualTo("true");

        ProcessVariableEntity shout = variable(processInstanceId, "shout");
        assertThat(shout.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(shout.getTextValue()).isEqualTo("HI");

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("scriptSum") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("scriptShout") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void scriptTaskEvaluatesCamunda8ZeebeScriptExpression() throws Exception {
        // Camunda 8 style: the script is carried by <zeebe:script expression="=a + b" resultVariable="sum">
        // in extensionElements (instead of an inline <bpmn:script> child).
        String bpmn = Files.readString(Paths.get("src/test/files/test-script-task-zeebe.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(
            var("a", ProcessVariableType.LONG, "2"),
            var("b", ProcessVariableType.LONG, "3")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        ProcessVariableEntity sum = variable(processInstanceId, "sum");
        assertThat(sum.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(sum.getTextValue()).isEqualTo("5");
    }

    @Transactional
    @Test
    void invalidScriptRaisesIncidentInsteadOfStallingSilently() throws Exception {
        // a syntactically invalid FEEL script must not crash the engine: the activity is left
        // un-completed and an incident is recorded on the script task.
        String bpmn = Files.readString(Paths.get("src/test/files/test-script-task-invalid.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        ActivityEntity scriptBad = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("scriptBad"))
            .findFirst().orElseThrow();

        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(scriptBad.getId()))
            .toList();
        assertThat(incidents).hasSize(1);
    }
}
