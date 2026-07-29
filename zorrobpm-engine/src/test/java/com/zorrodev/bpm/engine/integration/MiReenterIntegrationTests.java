package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
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
 * Reproduction of the stall/422 bug: when a multi-instance user task is active
 * and one instance completes with CLOSE action, the flow goes through a service task
 * and loops back to re-enter the same multi-instance user task.
 * <p>
 * The BPMN model (test-mi-reenter.bpmn) mirrors the user's controlProcess:
 * start → srvSetup → parallelGateway → miTask (MI userTask, inputCollection=executors)
 *                                    → endNotify
 * miTask → gwDecision → srvParentClose (if action=CLOSE)
 * gwDecision → srvSetup (if action=ADD_EXECUTORS) [loop]
 * srvParentClose → gwParent → miTask (if needReturn=true) [RE-ENTRY]
 *                          → endProcess [default]
 * miTask has non-interrupting boundary timer tmrCheck with timeDate=dueDate
 * <p>
 * KEY FINDING: ZorroBPM engine aggregates ALL MI instances before proceeding to
 * outgoing flows (join semantics). Individual instances do NOT follow outgoing
 * flows independently. This is DIFFERENT from Camunda 8 where each MI instance
 * follows its own outgoing flow.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiReenterIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Transactional
    @Test
    void miReentryAfterAllInstancesComplete() throws Exception {
        // KEY TEST: complete ALL MI instances first, then verify re-entry works
        String bpmn = Files.readString(Paths.get("src/test/files/test-mi-reenter.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");

        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2027-01-01T00:00:00Z");

        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("true");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Complete srvSetup
        ActivityEntity setupTask = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("srvSetup") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeServiceTask(setupTask.getId(), List.of());

        // Verify MI user task has 2 instances
        List<ActivityEntity> miInstances = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .collect(Collectors.toList());
        assertThat(miInstances).as("Should have 2 MI instances").hasSize(2);

        // Complete ALL MI instances with action=CLOSE (ZorroBPM aggregates, so only
        // the aggregated flow proceeds after ALL instances complete)
        ProcessVariable action = new ProcessVariable();
        action.setName("action");
        action.setType(ProcessVariableType.STRING);
        action.setValue("CLOSE");

        for (ActivityEntity mi : miInstances) {
            runtimeService.completeUserTask(mi.getId(), List.of(action));
        }

        // After ALL MI instances complete, the aggregated flow should reach srvParentClose
        ActivityEntity parentClose = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("srvParentClose") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElse(null);
        assertThat(parentClose).as("srvParentClose should be created after ALL MI instances complete").isNotNull();

        // Complete srvParentClose — this triggers re-entry to miTask via gwParent
        runtimeService.completeServiceTask(parentClose.getId(), List.of());

        // After re-entry, verify new MI instances are created (fresh batch)
        List<ActivityEntity> newMiInstances = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .collect(Collectors.toList());
        assertThat(newMiInstances).as("Re-entry should create new batch of MI instances").isNotEmpty();
    }

    @Transactional
    @Test
    void miCompleteAllThenAddExecutorsLoop() throws Exception {
        // KEY TEST: ADD_EXECUTORS loop — complete all MI instances, verify loop back to srvSetup
        String bpmn = Files.readString(Paths.get("src/test/files/test-mi-reenter.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\"]");

        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2027-01-01T00:00:00Z");

        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("false");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Complete srvSetup
        ActivityEntity setupTask = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("srvSetup") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeServiceTask(setupTask.getId(), List.of());

        // Verify MI user task has 1 instance
        List<ActivityEntity> miInstances = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .collect(Collectors.toList());
        assertThat(miInstances).as("Should have 1 MI instance").hasSize(1);

        // Complete the MI instance with ADD_EXECUTORS action
        ProcessVariable action = new ProcessVariable();
        action.setName("action");
        action.setType(ProcessVariableType.STRING);
        action.setValue("ADD_EXECUTORS");

        runtimeService.completeUserTask(miInstances.get(0).getId(), List.of(action));

        // The aggregated flow should loop back to srvSetup
        ActivityEntity srvSetupAgain = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("srvSetup") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElse(null);
        assertThat(srvSetupAgain).as("ADD_EXECUTORS should loop back to srvSetup").isNotNull();
    }

    @Transactional
    @Test
    void miNonInterruptingBoundaryTimerIsCreated() throws Exception {
        // KEY TEST: non-interrupting boundary timer on MI task creates timer_jobs
        String bpmn = Files.readString(Paths.get("src/test/files/test-mi-reenter.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");

        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2027-01-01T00:00:00Z");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Complete srvSetup
        ActivityEntity setupTask = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("srvSetup") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeServiceTask(setupTask.getId(), List.of());

        // Check that boundary timer jobs were created for each MI instance
        List<TimerJobEntity> timerJobs = timerJobRepository.findAll().stream()
            .filter(j -> "tmrCheck".equals(j.getBoundaryElementId()))
            .toList();
        // Non-interrupting boundary timer on MI should create 2 timer jobs (one per MI instance)
        assertThat(timerJobs).as("Non-interrupting boundary timer should create 1 timer job per MI instance").hasSize(2);
    }
}
