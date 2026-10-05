package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
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

/**
 * WO-C8-34 (CR-02): terminate is scope-confined.
 * <ul>
 *   <li>Root scope (existing {@code test-terminate.bpmn}): whole instance ends — regression guard.</li>
 *   <li>Nested scope ({@code test-terminate-subscope.bpmn}, external-review repro): completing only
 *       the INNER task fires terminate inside the subprocess — the OUTER parallel branch survives
 *       (still active), only the subprocess scope is closed, the instance keeps running.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TerminateScopeIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void terminateInSubprocessScope_doesNotCancelOuterBranch() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-terminate-subscope.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // two user tasks parked: outerTask (parallel branch A) + innerTask (subprocess branch B)
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        List<UserTask> parked = queryService.findUserTasks(query, null).getData();
        assertThat(parked).hasSize(2);
        UserTask inner = parked.stream()
            .filter(t -> "innerTask".equals(t.getCode()))
            .findFirst().orElseThrow();
        UserTask outer = parked.stream()
            .filter(t -> "outerTask".equals(t.getCode()))
            .findFirst().orElseThrow();

        // completing ONLY the inner task fires terminate inside the subprocess scope
        runtimeService.completeUserTask(inner.getId(), List.of());

        // the subprocess container is closed (cancelled, not completed normally) …
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> "sub1".equals(a.getBpmnElementId())
            && a.getStatus() == ActivityStatus.CANCELLED);

        // … the OUTER branch survives: still parked, never cancelled …
        ActivityEntity outerRow = activities.stream()
            .filter(a -> outer.getId().equals(a.getId()))
            .findFirst().orElseThrow();
        assertThat(outerRow.getStatus()).isEqualTo(ActivityStatus.CREATED);

        // … and the instance keeps running (root terminate would have completed it).
        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNull();
    }

    @Transactional
    @Test
    void terminateInRootScope_stillCompletesWholeInstance() throws Exception {
        // regression guard for the pre-existing behaviour (TerminateEndIntegrationTests topology).
        String bpmn = Files.readString(Paths.get("src/test/files/test-terminate.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
    }
}
