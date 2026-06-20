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
public class ConditionalEventIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ProcessVariable bool(String name, boolean value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(Boolean.toString(value));
        return v;
    }

    private List<ActivityEntity> activitiesOf(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    private ActivityEntity active(UUID processInstanceId, String bpmnElementId) {
        return activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
    }

    @Transactional
    @Test
    void conditionalCatchFiresWhenAVariableChangeMakesItTrue() throws Exception {
        // parallel split: branch A's user task sets approved=true; branch B parks on a conditional catch
        // (approved = true). Completing the user task re-evaluates the condition, fires the catch, and the
        // join completes.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-catch.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // branch B is parked on the conditional catch (condition not yet true)
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // complete branch A's user task with approved=true -> the conditional catch fires
        ActivityEntity setApproved = active(processInstanceId, "setApproved");
        runtimeService.completeUserTask(setApproved.getId(), List.of(bool("approved", true)));

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("condCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("join") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void conditionalCatchPassesThroughWhenConditionAlreadyTrueOnEntry() throws Exception {
        // started with approved=true on a linear process, the conditional catch is satisfied the moment the
        // token reaches it, so it passes straight through and the instance completes without any external
        // trigger.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-catch-entry.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(bool("approved", true)));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("condCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void conditionalBoundaryFiresInterruptingWhenItsConditionBecomesTrue() throws Exception {
        // host "work" carries an interrupting conditional boundary (abort = true). A parallel branch's user
        // task sets abort=true; completing it re-evaluates the boundary, which interrupts the host and routes
        // flow to the boundary's end.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-boundary.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // both hosts are parked: the work task (with the conditional boundary) and the trigger task
        assertThat(active(processInstanceId, "work").getStatus()).isEqualTo(ActivityStatus.CREATED);
        ActivityEntity trigger = active(processInstanceId, "trigger");

        runtimeService.completeUserTask(trigger.getId(), List.of(bool("abort", true)));

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // the host was interrupted and flow continued from the boundary
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("work") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
