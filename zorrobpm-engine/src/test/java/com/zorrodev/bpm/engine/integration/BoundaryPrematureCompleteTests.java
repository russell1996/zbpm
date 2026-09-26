package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-6: Non-interrupting boundary timer causes premature process instance completion.
 * <p>
 * Root cause: {@code FlowNavigator.finishBranch()} treats a child token (created by a
 * non-interrupting boundary event) with {@code pendingBranches == null} as a "linear process"
 * and calls {@code completeProcessInstance()}, prematurely completing the instance while
 * the main flow's activities are still CREATED.
 * <p>
 * Structure: parallel gateway fork → [MI userTask with non-interrupting boundary timer]
 *                                → [endEvent]
 * The boundary timer → serviceTask → endEvent. When the boundary fires, a child token is
 * created. That child token reaching its end event should NOT complete the instance.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BoundaryPrematureCompleteTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    // ========== POF: GREEN scenario — instance stays running after boundary timer fires ==========
    // RED proof was demonstrated separately (WO-ENG-6 session): without the fix in
    // FlowNavigator.finishBranch(), the child token from a non-interrupting boundary event
    // reaches its end event and calls completeProcessInstance() prematurely. The RED test
    // boundaryTimerPrematurelyCompletesInstance (removed from commit) asserted the buggy
    // completedAt != null and passed on code without the fix.
    //
    // POF RED log (without fix):
    //   "Linear token (no pending branches), completing instance"
    //   Tests run: 1, Failures: 0
    //
    // POF GREEN log (with fix):
    //   "Child linear token ending — branch complete, instance continues"
    //   Tests run: 1, Failures: 0

    /**
     * GREEN (same scenario, fix applied): the non-interrupting boundary timer's child
     * token reaches its end event but does NOT complete the instance. The instance stays
     * RUNNING until the MI user task completes.
     * <p>
     * This test will pass AFTER the fix in {@code FlowNavigator.finishBranch()} is applied.
     * Before the fix, it would fail (instance would be prematurely completed).
     */
    @Transactional
    @Test
    void boundaryTimerDoesNotPrematurelyCompleteInstance() throws Exception {
        // 1. Deploy and start
        String bpmn = Files.readString(Paths.get("src/test/files/test-eng-6-boundary-premature.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");

        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2099-01-01T00:00:00Z"); // future — no automatic timer fire

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // 2. Verify MI instances
        List<ActivityEntity> miInstances = findActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(miInstances).as("MI userTask should have 2 instances").hasSize(2);

        // 3. Fire boundary timer
        UUID miTaskActivityId = miInstances.get(0).getId();
        activityService.fireBoundaryTimer(miTaskActivityId, "tmrCheck");

        // 4. Complete srvNotify
        ActivityEntity srvNotify = findActivity(piId, "srvNotify", ActivityStatus.CREATED);
        assertThat(srvNotify).as("srvNotify should be created by boundary timer").isNotNull();
        runtimeService.completeServiceTask(srvNotify.getId(), List.of());

        // 5. GREEN: Instance should still be RUNNING (not prematurely completed)
        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt())
            .as("GREEN: Instance should NOT be completed prematurely by boundary timer")
            .isNull();

        // MI tasks should still be CREATED
        List<ActivityEntity> miStillOpen = findActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(miStillOpen)
            .as("GREEN: miTask instances should still be CREATED")
            .hasSize(2);

        // 6. Complete both MI instances — instance should complete normally
        ProcessVariable action = new ProcessVariable();
        action.setName("action");
        action.setType(ProcessVariableType.STRING);
        action.setValue("CLOSE");

        for (ActivityEntity mi : miInstances) {
            runtimeService.completeUserTask(mi.getId(), List.of(action));
        }

        // Now the instance should be completed (MI completed → endA → finishBranch)
        pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt())
            .as("GREEN: Instance should be completed after all MI instances complete")
            .isNotNull();
    }

    // ========== Test with controlProcess (full structure with loop-back) ==========

    /**
     * Full controlProcess scenario: parallel fork + loop-back + non-interrupting boundary timer.
     * Verifies that the boundary timer does NOT prematurely complete the instance.
     */
    @Transactional
    @Test
    void controlProcessBoundaryTimerDoesNotPrematurelyComplete() throws Exception {
        ProcessVariable executors = new ProcessVariable();
        executors.setName("currentExecutors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");

        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2099-01-01T00:00:00Z"); // future

        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("false");

        String bpmn = Files.readString(Paths.get("src/test/files/controlProcess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Complete srvCreateTask → gwCreateTask (fork)
        ActivityEntity srvCreate = findActivity(piId, "srvCreateTask", ActivityStatus.CREATED);
        assertThat(srvCreate).as("srvCreateTask should be CREATED").isNotNull();
        runtimeService.completeServiceTask(srvCreate.getId(), List.of());

        // After fork: complete parallel branches (notification + work task)
        ActivityEntity srvNotification = findActivity(piId, "srvNotification", ActivityStatus.CREATED);
        if (srvNotification != null) {
            runtimeService.completeServiceTask(srvNotification.getId(), List.of());
        }
        ActivityEntity srvWorkTask = findActivity(piId, "srvWorkTask", ActivityStatus.CREATED);
        if (srvWorkTask != null) {
            runtimeService.completeServiceTask(srvWorkTask.getId(), List.of());
        }

        // Verify utExecute MI instances
        List<ActivityEntity> miInstances = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(miInstances).as("utExecute should have 2 MI instances").hasSize(2);

        // Fire boundary timer on one MI instance
        activityService.fireBoundaryTimer(miInstances.get(0).getId(), "tmrCheck");

        // Complete the boundary path
        ActivityEntity srvNotify = findActivity(piId, "srvNotifyOverdue", ActivityStatus.CREATED);
        if (srvNotify != null) {
            runtimeService.completeServiceTask(srvNotify.getId(), List.of());
        }

        // GREEN: Instance should NOT be completed
        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt())
            .as("GREEN: Instance should NOT be completed by boundary timer in controlProcess")
            .isNull();

        // miTask instances should still be CREATED
        List<ActivityEntity> miStillOpen = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(miStillOpen)
            .as("GREEN: utExecute instances should still be CREATED")
            .hasSize(2);
    }

    // ========== HELPERS ==========

    private ActivityEntity findActivity(UUID piId, String elementId, ActivityStatus status) {
        return findActivities(piId, elementId, status).stream().findFirst().orElse(null);
    }

    private List<ActivityEntity> findActivities(UUID piId, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .collect(Collectors.toList());
    }
}
