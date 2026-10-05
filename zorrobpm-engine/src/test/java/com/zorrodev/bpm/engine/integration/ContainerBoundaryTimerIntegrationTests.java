package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-34 (CR-04 + criterion 6): boundary events must be armed on EVERY host
 * type that can carry them — the review table listed five hosts that parsed their
 * boundaries but never called {@code BoundaryScheduler}: embedded subprocess,
 * call activity, ad-hoc subprocess, business rule task, manual task. Before the
 * fix no timer-job row existed for any of them (the scheduler was only wired on
 * UserTask/ServiceTask hosts).
 *
 * <p>Pattern copied from {@code ServiceTaskBoundaryTimerIntegrationTests}
 * (WO-DIFF-4): the background poller is parked in tests, so the fire is driven
 * explicitly through {@link TimerJobExecutor#fire} with the REAL persisted row —
 * the same call the poller would make, minus the wall-clock wait.
 *
 * <p>Criterion 6 ({@code BoundaryScheduler.isInterrupting}): the scheduler must
 * NOT filter by interrupting — both kinds are armed, the split happens at fire
 * time in {@code EventTrigger.fireBoundary}. {@link
 * #nonInterrupting_boundaryOnSubprocessContainer_isAlsoScheduled()} is the
 * behavioural proof (a row for a {@code cancelActivity="false"} boundary).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ContainerBoundaryTimerIntegrationTests {

    /**
     * Process instances started by THIS class. Boundary timer rows survive the
     * test and are visible to every later class in the shared H2 — a leftover
     * PT1H row broke {@code TimerMessageQueryIntegrationTests.pendingTimerJobIsListed}
     * ("expecting empty") and {@code EventSubProcessIntegrationTests}' time-window
     * cleanup (WO-REL-13 pattern, which NPEs once it sees any row). Cleanup is
     * therefore scoped to OUR OWN instance ids, never by boundary id: the
     * interrupting subprocess fixture ({@code subTimeout}) is shared with
     * {@code SubprocessBoundaryTimerIntegrationTests}, and deleting by boundary
     * id would reach into a foreign class's rows.
     */
    private final List<UUID> ownInstances = new ArrayList<>();

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
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanOwnTimerState() {
        // Same leak class as ServiceTaskBoundaryTimerIntegrationTests.cleanOwnTimerState,
        // but scoped by OUR OWN instance ids instead of boundary ids: the
        // interrupting-subprocess fixture's boundary id (subTimeout) is shared
        // with SubprocessBoundaryTimerIntegrationTests, so deleting by boundary
        // id would delete a foreign class's rows.
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

    private List<TimerJobEntity> pendingTimers(UUID hostActivityId, String boundaryElementId) {
        return timerJobRepository.findAll().stream()
            .filter(r -> hostActivityId.equals(r.getActivityId()))
            .filter(r -> boundaryElementId.equals(r.getBoundaryElementId()))
            .filter(r -> !r.isFired())
            .toList();
    }

    private ActivityStatus activityStatus(UUID pi, String bpmnElementId) {
        return activityStatusOrNull(pi, bpmnElementId);
    }

    private ActivityStatus activityStatusOrNull(UUID pi, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> bpmnElementId.equals(a.getBpmnElementId()))
            .map(ActivityEntity::getStatus)
            .findFirst().orElse(null);
    }

    private UserTask userTask(UUID pi, String name) {
        UserTaskQuery q = new UserTaskQuery();
        q.setProcessInstanceId(pi);
        return queryService.findUserTasks(q, null).getData().stream()
            .filter(t -> name.equals(t.getName()))
            .findFirst().orElse(null);
    }

    private ServiceTask serviceTask(UUID pi, String job) {
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(pi);
        return queryService.findServiceTasks(q, null).getData().stream()
            .filter(t -> job.equals(t.getJob()))
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

    // ── criterion 2: one host per row of the review table ────────────────────

    /**
     * Criterion 2, ad-hoc subprocess host: a PT1H timer boundary on the container
     * produces exactly one pending timer-job row bound to the container row.
     * RED before the fix: no row at all (AdHocSubProcessHandler never armed it).
     */
    @Test
    void boundaryOnAdhocSubprocessContainer_isScheduled() throws Exception {
        UUID pi = deployAndStart("test-c834-adhoc-boundary-timer.bpmn");

        ActivityEntity host = hostActivity(pi, "adhoc1");

        assertThat(pendingTimers(host.getId(), "adhocTimeout"))
            .as("timer job for the ad-hoc-container boundary").hasSize(1);
    }

    /**
     * Criterion 2, business rule task host (same row of the table). RED before
     * the fix: no row (SyncTaskHandler.BusinessRuleTask never armed it).
     */
    @Test
    void boundaryOnBusinessRuleTaskHost_isScheduled() throws Exception {
        UUID pi = deployAndStart("test-c834-br-boundary-timer.bpmn");

        ActivityEntity host = hostActivity(pi, "decide1");

        assertThat(pendingTimers(host.getId(), "brTimeout"))
            .as("timer job for the business-rule-host boundary").hasSize(1);
    }

    /**
     * Criterion 2, manual task host (same row of the table). The manual task
     * completes synchronously, so the armed job self-skips at fire time — what is
     * under test is the registration itself.
     */
    @Test
    void boundaryOnManualTaskHost_isScheduled() throws Exception {
        UUID pi = deployAndStart("test-c834-manual-boundary-timer.bpmn");

        ActivityEntity host = hostActivity(pi, "manual1");

        assertThat(pendingTimers(host.getId(), "manualTimeout"))
            .as("timer job for the manual-host boundary").hasSize(1);
    }

    // ── criterion 2: the review's own PT1H scenarios, end to end ──────────────

    /**
     * Criterion 2 (review scenario, PT1H on an embedded subprocess), end to end:
     * the boundary fires while the inner user task is still open — the container
     * host AND its inner work are cancelled (scope-confined, CR-05 mechanism),
     * the boundary path runs and the PARENT continues to its end event.
     *
     * <p>Before the fix the timer never existed, so this scenario was unreachable:
     * the subprocess could not be timed out at all.
     */
    @Test
    void boundaryOnSubprocessContainer_fires_hostCancelled_parentContinues() throws Exception {
        UUID pi = deployAndStart("test-c834-sub-boundary-timer.bpmn");

        ActivityEntity host = hostActivity(pi, "sub1");
        UserTask inner = userTask(pi, "work");
        assertThat(inner).as("inner user task parked inside the subprocess").isNotNull();
        assertThat(pendingTimers(host.getId(), "subTimeout")).hasSize(1);

        fireScheduledBoundary(pi, "subTimeout");

        assertThat(activityStatus(pi, "sub1")).isEqualTo(ActivityStatus.CANCELLED);
        assertThat(activityStatus(pi, "work")).as("inner work cancelled with its scope").isEqualTo(ActivityStatus.CANCELLED);
        assertThat(activityStatus(pi, "endTimeout")).as("boundary continuation ran").isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("parent process continued after the container was timed out").isNotNull();
    }

    /**
     * Criterion 2, "снимается на выходе": once the container finished normally,
     * its already-armed timer row is inert — firing it must NOT activate the
     * boundary path. This is the same teardown contract UserTaskHandler/
     * ServiceTaskHandler already rely on (fireBoundary skips finished hosts).
     */
    @Test
    void boundaryOnSubprocessContainer_afterContainerFinished_doesNotFire() throws Exception {
        UUID pi = deployAndStart("test-c834-sub-boundary-timer.bpmn");

        UserTask inner = userTask(pi, "work");
        assertThat(inner).as("inner user task parked inside the subprocess").isNotNull();
        assertThat(pendingTimers(hostActivity(pi, "sub1").getId(), "subTimeout")).hasSize(1);

        // normal exit of the container
        runtimeService.completeUserTask(inner.getId(), List.of());
        assertThat(activityStatus(pi, "sub1")).isEqualTo(ActivityStatus.COMPLETED);

        // the still-present armed row must be inert now
        fireScheduledBoundary(pi, "subTimeout");

        assertThat(activityStatusOrNull(pi, "endTimeout"))
            .as("boundary path must NOT activate after the host completed").isNull();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
    }

    /**
     * Criterion 2 (review scenario, PT1H on a call activity), end to end: the
     * timer is armed on the call-activity container row while the child parks on
     * its user task; firing it cancels this call's work (parent-side host row and
     * the child instance) and the boundary path parks the escape task.
     *
     * <p>The child scope is what makes this CR-04+CR-05 at once: the child instance
     * belongs to the call's scope, so it dies with it — while the parent process
     * itself keeps running.
     */
    @Test
    void boundaryOnCallActivityContainer_fires_callScopeCancelled_parentSurvives() throws Exception {
        // child first: the call activity resolves its target by processId
        String child = Files.readString(Paths.get("src/test/files/test-c834-call-child.bpmn"));
        processDefinitionService.addProcessDefinition(child);

        String parent = Files.readString(Paths.get("src/test/files/test-c834-call-boundary-timer.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(parent);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        ownInstances.add(pi);

        ActivityEntity host = hostActivity(pi, "call1");
        assertThat(pendingTimers(host.getId(), "callTimeout"))
            .as("timer job for the call-activity-container boundary").hasSize(1);

        fireScheduledBoundary(pi, "callTimeout");

        assertThat(activityStatus(pi, "call1")).isEqualTo(ActivityStatus.CANCELLED);

        ServiceTask escape = serviceTask(pi, "c834-after-timeout-job");
        assertThat(escape).as("boundary continuation parked the escape task").isNotNull();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("parent instance itself is NOT completed by the boundary fire").isNull();

        runtimeService.completeServiceTask(escape.getId(), List.of());

        ProcessInstance done = queryService.getProcessInstance(pi);
        assertThat(done.getCompletedAt()).as("parent completed through the boundary path").isNotNull();
    }

    // ── criterion 6: isInterrupting must not gate registration ────────────────

    /**
     * Criterion 6: a NON-interrupting boundary ({@code cancelActivity="false"}) is
     * armed exactly like an interrupting one — {@code BoundaryScheduler} must not
     * read {@code isInterrupting} at registration time (the split belongs to
     * {@code EventTrigger.fireBoundary}). RED if the scheduler ever filters here.
     */
    @Test
    void nonInterrupting_boundaryOnSubprocessContainer_isAlsoScheduled() throws Exception {
        UUID pi = deployAndStart("test-c834-sub-boundary-timer-ni.bpmn");

        ActivityEntity host = hostActivity(pi, "sub1");

        assertThat(pendingTimers(host.getId(), "subTimeoutNi"))
            .as("non-interrupting timer boundary must be armed too").hasSize(1);
    }
}