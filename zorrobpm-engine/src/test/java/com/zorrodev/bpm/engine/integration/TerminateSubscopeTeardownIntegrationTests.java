package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-37 (C37-4): terminate inside an embedded subprocess closes the scope
 * like a normal exit — the container's output mappings are promoted and the
 * scope locals are dropped (the same teardown {@code FlowNavigator.finishBranch}
 * does), while the container row stays CANCELLED and the parent flow continues
 * past the subprocess.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TerminateSubscopeTeardownIntegrationTests {

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

    private Map<String, String> rootVars(UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .collect(Collectors.toMap(ProcessVariableEntity::getName, ProcessVariableEntity::getTextValue,
                (a, b) -> b));
    }

    /**
     * C37-4: completing the inner task fires terminate inside the scope. The
     * output mapping ({@code =subLocal -> subOut}) is promoted to root, the
     * scope-local {@code subLocal} row is dropped, the container row is
     * CANCELLED (pre-existing semantic, pinned by
     * {@code TerminateScopeIntegrationTests}), and the parent flow continues to
     * the end event. RED before the fix: no {@code subOut} at root and the
     * {@code subLocal} scope row survives.
     */
    @Transactional
    @Test
    void terminateInSubprocess_promotesOutputMappingsAndDropsScopeLocals() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c837-terminate-subscope-teardown.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable parentVar = new ProcessVariable();
        parentVar.setName("parentVar");
        parentVar.setType(ProcessVariableType.STRING);
        parentVar.setValue("p0");
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(parentVar));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        List<UserTask> parked = queryService.findUserTasks(query, null).getData();
        assertThat(parked).hasSize(1);
        assertThat(parked.get(0).getCode()).isEqualTo("innerTask");

        // The input mapping seeded the scope-local before the terminate fires.
        UUID subActivityId = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> "sub1".equals(a.getBpmnElementId()))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, subActivityId).stream()
            .map(ProcessVariableEntity::getName))
            .as("input-seeded scope local before terminate")
            .contains("subLocal");

        runtimeService.completeUserTask(parked.get(0).getId(), List.of());

        // Output mapping promoted to root …
        assertThat(rootVars(processInstanceId)).containsEntry("subOut", "p0");
        // … scope locals dropped …
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, subActivityId))
            .as("scope locals after terminate-teardown")
            .isEmpty();
        // … container row still CANCELLED (terminate semantic, unchanged) …
        ActivityStatus subStatus = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> "sub1".equals(a.getBpmnElementId()))
            .map(ActivityEntity::getStatus)
            .findFirst().orElse(null);
        assertThat(subStatus).isEqualTo(ActivityStatus.CANCELLED);
        // … and the parent flow continued past the subprocess to the end event.
        ProcessInstance instance = queryService.getProcessInstance(processInstanceId);
        assertThat(instance.getCompletedAt()).isNotNull();
    }
}
