package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class EventSubProcessIntegrationTests {

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

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Autowired
    private TimerJobExecutor timerJobExecutor;

    @Autowired
    private DBService dbService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID createdProcessInstanceId;

    /**
     * WO-REL-13: plain timer jobs (intermediate catch / boundary / cycle) are created WITHOUT a
     * processInstanceId (only event-subprocess timer jobs carry one), so per-instance cleanup and
     * filtering is impossible. Instead we scope by creation time: every job of this test is created
     * after testStartedAt, foreign jobs are strictly older. @AfterEach deletes the whole window.
     */
    private Instant testStartedAt;

    /** Runs the action in its own committed transaction (WO-REL-13: fires must see committed rows). */
    private void inNewTx(Runnable action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            action.run();
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        // WO-REL-13: tests commit their own rows now (REQUIRES_NEW fires), so the shared H2
        // database must be cleaned explicitly — otherwise other tests see stale timer jobs
        // (TimerMessageQuery counts fired=true rows; ControlProcess/MiReenter expect an exact
        // findAll() size). Delete the whole creation-time window, including un-fired re-arms.
        Instant since = testStartedAt;
        inNewTx(() -> {
            List<TimerJobEntity> mine = timerJobRepository.findAll().stream()
                .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(since))
                .toList();
            timerJobRepository.deleteAll(mine);
        });
        createdProcessInstanceId = null;
    }

    private List<ActivityEntity> activitiesOf(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    private UUID start(UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void interruptingMessageEventSubProcessCancelsMainFlowAndRunsHandler() throws Exception {
        // the main flow parks at mainTask; correlating "cancelOrder" triggers the interrupting event
        // sub-process, which cancels the main flow and runs to its own end, completing the instance.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        activityService.correlateMessage("cancelOrder", processInstanceId, List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // main task was interrupted; the handler ran to its end; the main end was never reached
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("mainEnd"));
    }

    @Transactional
    @Test
    void nonInterruptingMessageEventSubProcessRunsAlongsideMainFlowAndCanFireRepeatedly() throws Exception {
        // the main flow keeps running; each "ping" spawns a handler in its own scope. The subscription is
        // not consumed, so the handler can fire more than once. The main flow still completes normally.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-noninterrupting.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        // fire the non-interrupting handler twice; the main flow must stay alive (instance not completed)
        activityService.correlateMessage("ping", processInstanceId, List.of());
        activityService.correlateMessage("ping", processInstanceId, List.of());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // the main task was never interrupted; completing it finishes the instance normally
        ActivityEntity mainTask = activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(mainTask.getId(), List.of());

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // the handler ran twice (two evEnd activities) and the main flow reached its own end
        assertThat(activities).filteredOn(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED).hasSize(2);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void mainFlowCompletesNormallyWhenNoTriggeringMessageArrives() throws Exception {
        // without the triggering message the event sub-process stays dormant and the main flow completes
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        ActivityEntity mainTask = activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(mainTask.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("evEnd"));
    }

    @Transactional
    @Test
    void signalTriggeredEventSubProcessFiresOnSignalBroadcast() throws Exception {
        // a parallel branch throws "cancelSig" while the main task is parked; the signal-started event
        // sub-process interrupts the main flow and runs the handler.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-signal.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("mainEnd"));
    }

    @Transactional
    @Test
    void errorTriggeredEventSubProcessHandlesAThrownError() throws Exception {
        // the main flow throws error "E-1"; the error-started event sub-process catches it and runs to its
        // end (instead of the error becoming an unhandled incident).
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-error.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    /**
     * Fires due timer jobs created by THIS test only (WO-REL-13: REQUIRES_NEW fire commits
     * per-job, so firing jobs of other tests would leave fired=true rows that pollute the shared
     * H2 database). Scoped by creation time: every job of this test is created after the moment
     * the test started, while foreign jobs are strictly older.
     */
    private void fireDueTimers() {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).stream()
            .filter(j -> j.getCreatedAt() != null && !j.getCreatedAt().isBefore(testStartedAt))
            .forEach(j -> timerJobExecutor.fire(j));
    }

    @Test
    void timerTriggeredEventSubProcessFiresWhenTheTimerIsDue() throws Exception {
        testStartedAt = Instant.now();
        // the event sub-process has a PT0S timer (immediately due); the main task parks, then firing the
        // due timers interrupts the main flow and runs the handler.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-timer.bpmn"));

        UUID[] processInstanceId = new UUID[1];
        inNewTx(() -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            processInstanceId[0] = start(model.getId());
        });
        createdProcessInstanceId = processInstanceId[0];
        assertThat(queryService.getProcessInstance(processInstanceId[0]).getCompletedAt()).isNull();

        inNewTx(this::fireDueTimers);

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId[0]);
        assertThat(pi.getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(processInstanceId[0]);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
