package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-3: Multi-instance user task boundary events.
 * Tagged @Tag("pg") via PostgresIT — excluded from default CI.
 */
public class MiBoundaryTimerPgIT extends PostgresIT {

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

    @Autowired
    private TimerJobExecutor timerJobExecutor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    /** Builds a SQL IN clause from UUIDs (e.g. "'u1','u2'"). */
    private static String inClause(List<UUID> ids) {
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    }

    /** Deploys and starts a BPMN process in a transaction. */
    private UUID startProcess(String bpmnFile) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #1: MI user task with boundary timer → ≥1 timer_job
    // Criterion #2: Interrupting timer fires → MI instances cancelled, flow to boundary
    // ─────────────────────────────────────────────────────────────────

    @Test
    void miBoundaryTimer_createsTimerJobs_andInterruptingCancelsInstances() throws Exception {
        UUID processInstanceId = startProcess("test-mi-boundary.bpmn");

        // Find MI task activities for this process instance
        List<ActivityEntity> miActivities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .toList();

        // Two parallel MI instances should be active
        assertThat(miActivities).hasSize(2);

        // ── Criterion #1: timer_jobs exist for MI boundary timer ──
        List<UUID> miActivityIds = miActivities.stream().map(ActivityEntity::getId).toList();
        String incl = inClause(miActivityIds);
        Integer timerJobCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE activity_id IN (" + incl
                + ") AND boundary_element_id IS NOT NULL",
            Integer.class);
        assertThat(timerJobCount).isGreaterThanOrEqualTo(1);

        // ── Criterion #2: fire the interrupting boundary timer ──
        List<UUID> timerJobIds = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id IN (" + incl
                + ") AND boundary_element_id IS NOT NULL LIMIT 1",
            UUID.class);
        assertThat(timerJobIds).isNotEmpty();

        UUID timerJobId = timerJobIds.get(0);

        // Fire the timer directly. WO-REL-13 (R-03): TimerJobExecutor.fire runs in its OWN
        // REQUIRES_NEW transaction, so it must NOT be called inside an outer transaction that
        // already holds a lock on this timer_jobs row (that would deadlock: the suspended outer
        // tx keeps the row lock until fire returns). The job is not yet due, so the concurrent
        // TimerScheduler poll (due_at <= now()) will not grab it between setup and fire.
        TimerJobEntity entity = timerJobRepository.findById(timerJobId).orElseThrow();
        TimerJob job = new TimerJob();
        job.setId(entity.getId());
        job.setActivityId(entity.getActivityId());
        job.setDueAt(entity.getDueAt());
        job.setBoundaryElementId(entity.getBoundaryElementId());
        job.setProcessInstanceId(entity.getProcessInstanceId());
        job.setRemainingCount(entity.getRemainingCount());

        timerJobExecutor.fire(job);

        // After firing, verify that:
        //   - the boundary host MI task was cancelled (interrupting)
        //   - the boundary end event activity exists (flow went from boundary->boundaryEnd)
        //   - the process instance was completed (end event terminates the instance)
        List<ActivityEntity> allActivities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();

        assertThat(allActivities)
            .filteredOn(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CANCELLED)
            .hasSize(2);

        assertThat(allActivities)
            .anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd"));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #3: Signal boundary on MI registers subscription
    // ─────────────────────────────────────────────────────────────────

    @Test
    void miBoundarySignal_createsSubscription() throws Exception {
        UUID processInstanceId = startProcess("test-mi-boundary-signal.bpmn");

        // Find MI task activities
        List<ActivityEntity> miActivities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .toList();
        assertThat(miActivities).isNotEmpty();

        // Signal subscriptions with boundaryElementId should exist for MI activities
        List<UUID> miActivityIds = miActivities.stream().map(ActivityEntity::getId).toList();
        String incl = inClause(miActivityIds);
        Integer subCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM signal_subscriptions WHERE activity_id IN (" + incl
                + ") AND boundary_element_id IS NOT NULL",
            Integer.class);
        assertThat(subCount).isGreaterThanOrEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #4: Non-MI user task boundary still works (regression)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void nonMiBoundary_stillWorks() throws Exception {
        UUID processInstanceId = startProcess("test-boundary.bpmn");

        // Find the user task activity
        List<ActivityEntity> userTaskActivities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("userTask1"))
            .toList();
        assertThat(userTaskActivities).hasSize(1);

        UUID activityId = userTaskActivities.get(0).getId();

        // Timer job for boundary should exist on the non-MI user task
        Integer timerJobCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE activity_id = ? AND boundary_element_id IS NOT NULL",
            Integer.class, activityId);
        assertThat(timerJobCount).isEqualTo(1);
    }
}
