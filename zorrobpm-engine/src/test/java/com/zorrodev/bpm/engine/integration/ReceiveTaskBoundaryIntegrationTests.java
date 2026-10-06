package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-37 (C37-2): {@code receiveTask} arms boundary events like the other async
 * wait-state hosts (UserTask/ServiceTask pattern). {@code receiveTask} executes
 * through {@code MessageCatchHandler}, which never called {@code BoundaryScheduler} —
 * a timer boundary on a receive task never produced a timer-job row and never fired.
 *
 * <p>Fire path mirrors {@code ContainerBoundaryTimerIntegrationTests}: the background
 * poller is parked in tests, so the fire is driven explicitly through
 * {@code TimerJobExecutor#fire} with the REAL persisted row.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ReceiveTaskBoundaryIntegrationTests {

    private final List<UUID> ownInstances = new ArrayList<>();

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
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanOwnTimerState() {
        new TransactionTemplate(txManager).execute(s -> {
            timerJobRepository.findAll().stream()
                .filter(r -> ownInstances.contains(r.getProcessInstanceId()))
                .forEach(r -> timerJobRepository.deleteById(r.getId()));
            return null;
        });
        ownInstances.clear();
    }

    private UUID deployAndStart(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        ownInstances.add(pi);
        return pi;
    }

    private ActivityEntity hostActivity(UUID pi, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> bpmnElementId.equals(a.getBpmnElementId()))
            .findFirst().orElseThrow();
    }

    private ActivityStatus activityStatusOrNull(UUID pi, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> bpmnElementId.equals(a.getBpmnElementId()))
            .map(ActivityEntity::getStatus)
            .findFirst().orElse(null);
    }

    private void fireScheduledBoundary(UUID pi, String boundaryElementId) {
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> pi.equals(r.getProcessInstanceId()))
            .filter(r -> boundaryElementId.equals(r.getBoundaryElementId()))
            .filter(r -> !r.isFired())
            .toList();
        assertThat(rows).as("scheduled boundary timer job for " + boundaryElementId).hasSize(1);
        TimerJobEntity row = rows.get(0);
        TimerJob job = new TimerJob();
        job.setId(row.getId());
        job.setActivityId(row.getActivityId());
        job.setDueAt(row.getDueAt());
        job.setCreatedAt(row.getCreatedAt());
        job.setBoundaryElementId(row.getBoundaryElementId());
        job.setProcessInstanceId(row.getProcessInstanceId());
        job.setRemainingCount(row.getRemainingCount());
        job.setExpression(row.getExpression());
        timerJobExecutor.fire(job);
    }

    /**
     * C37-2, interrupting: the timer boundary on the receive task is armed on
     * entry; firing it cancels the parked receive task and takes the boundary
     * path to completion. RED before the fix: no timer-job row is ever scheduled
     * (MessageCatchHandler never arms boundaries).
     */
    @Test
    void interruptingTimerBoundaryOnReceiveTask_firesAndCancelsHost() throws Exception {
        UUID pi = deployAndStart("test-c837-receivetask-boundary-timer.bpmn");

        ActivityEntity host = hostActivity(pi, "waitForHold");
        assertThat(host.getStatus()).isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);

        fireScheduledBoundary(pi, "recvTimeout");

        assertThat(activityStatusOrNull(pi, "waitForHold")).isEqualTo(ActivityStatus.CANCELLED);
        assertThat(activityStatusOrNull(pi, "endTimeout")).isEqualTo(ActivityStatus.COMPLETED);
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt()).isNotNull();
    }

    /**
     * C37-2, non-interrupting: firing leaves the parked receive task alive AND
     * runs the boundary branch; the later message still resumes the host and
     * completes the instance. RED before the fix: same missing row as above.
     */
    @Test
    void nonInterruptingTimerBoundaryOnReceiveTask_firesAndHostSurvives() throws Exception {
        UUID pi = deployAndStart("test-c837-receivetask-boundary-timer-nonint.bpmn");

        ActivityEntity host = hostActivity(pi, "waitForHold");
        assertThat(host.getStatus()).isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);

        fireScheduledBoundary(pi, "recvReminder");

        assertThat(activityStatusOrNull(pi, "endReminder")).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activityStatusOrNull(pi, "waitForHold"))
            .isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);

        activityService.correlateMessage("hold", pi, List.of());

        assertThat(activityStatusOrNull(pi, "waitForHold")).isEqualTo(ActivityStatus.COMPLETED);
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt()).isNotNull();
    }
}
