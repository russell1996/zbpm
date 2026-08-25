package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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
 * WO-ENG-12: implicit fork (serviceTask with 2+ outgoing, no gateway) must use
 * pendingBranches counter like explicit parallel gateway. Without it the first
 * branch to finish completes the instance while the second branch is still active.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ImplicitForkPendingBranchesIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ActivityEntity findActiveActivity(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow(() -> new AssertionError("active activity not found: " + bpmnElementId));
    }

    @Transactional
    @Test
    void criterion1_and_criterion2_implicitForkWaitsForBothBranches() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-implicit-fork-two-way.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // forkTask is the implicit fork (serviceTask with 2 outgoing)
        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // After forking, both branches should be active: serviceA and userB
        // Complete the short branch (serviceA -> endA)
        ActivityEntity serviceA = findActiveActivity(piId, "serviceA");
        runtimeService.completeServiceTask(serviceA.getId(), List.of());

        // Instance must still be RUNNING — userB branch is still active
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must stay RUNNING after only the short branch finished")
            .isNull();
        // userB should still be active
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("userB"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .count()).isOne();

        // Criterion 2: complete the long branch, instance should now complete
        ActivityEntity userB = findActiveActivity(piId, "userB");
        runtimeService.completeUserTask(userB.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must be COMPLETED after both branches finished")
            .isNotNull();
    }

    @Transactional
    @Test
    void criterion3_singleOutgoingDoesNotCreateExtraToken() throws Exception {
        // Sanity: a normal linear serviceTask with 1 outgoing must still complete normally
        // Use the same BPMN but verify that completing forkTask alone does not create stray tokens
        // We do this by checking that a simple linear flow (start -> service -> end) works
        String bpmn = Files.readString(Paths.get("src/test/files/test-implicit-fork-linear.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity task = findActiveActivity(piId, "singleTask");
        runtimeService.completeServiceTask(task.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("linear process must complete after single branch finishes")
            .isNotNull();
    }
}
