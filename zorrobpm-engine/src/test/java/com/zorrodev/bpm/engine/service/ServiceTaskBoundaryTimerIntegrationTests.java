package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-4 — Raxon findings #9/#10: interrupting + non-interrupting timer boundary
 * events on a SERVICE_TASK host, reproduced the same way the Raxon driver does it:
 * deploy via {@code POST /deployments}-equivalent, start via {@code POST
 * /process-instances}-equivalent, leave the guarded task's job open (no worker for it),
 * fire the boundary through the timer-job path, then drive the remaining tasks to
 * completion by job type — and assert the final instance state + variables.
 *
 * <p>The background {@code TimerScheduler} poller is parked in tests (see
 * {@code src/test/resources/application.properties}), so the fire is driven explicitly
 * through {@link TimerJobExecutor#fire} with the REAL scheduled row — the same call the
 * poller would make (claim + dispatch), minus the wall-clock wait.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ServiceTaskBoundaryTimerIntegrationTests {

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
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @Autowired
    private TimerJobExecutor timerJobExecutor;

    @org.junit.jupiter.api.AfterEach
    void cleanOwnTimerState() {
        // Same leak class as WO-REL-13 (EventSubProcessIntegrationTests.cleanup): this
        // class fires its timers explicitly, so fired=true rows linger in the shared H2
        // and break TimerMessageQueryIntegrationTests' "no fired rows" assertion.
        // Scoped to THIS class's instances only (job types diff4-*), never global.
        org.springframework.transaction.support.TransactionTemplate tt =
            new org.springframework.transaction.support.TransactionTemplate(txManager);
        tt.execute(s -> {
            timerJobRepository.findAll().stream()
                .filter(r -> "timeout".equals(r.getBoundaryElementId()))
                .filter(r -> {
                    try {
                        var a = activityRepository.findById(r.getActivityId());
                        return a.isPresent()
                            && ("diff4-work".equals(jobOf(a.get()))
                                || "diff4-ni-work".equals(jobOf(a.get())));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .forEach(r -> timerJobRepository.deleteById(r.getId()));
            return null;
        });
    }

    private String jobOf(ActivityEntity a) {
        try {
            var st = serviceTaskRepository.findById(a.getId());
            return st.map(s -> s.getJob()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private UUID deployAndStart(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private List<ServiceTask> serviceTasks(UUID processInstanceId) {
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(processInstanceId);
        PagedDataDTO<ServiceTask> page = queryService.findServiceTasks(q, null);
        return page.getData();
    }

    private ServiceTask byJob(List<ServiceTask> tasks, String job) {
        return tasks.stream().filter(t -> job.equals(t.getJob())).findFirst().orElse(null);
    }

    private ProcessVariable var(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    /**
     * Fires the scheduled boundary timer the same way the production poller would:
     * builds the {@link TimerJob} DTO from the REAL persisted row and calls
     * {@link TimerJobExecutor#fire} (claim + dispatch to {@code fireBoundaryTimer}).
     */
    private void fireScheduledBoundary(UUID processInstanceId, String boundaryElementId) {
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> processInstanceId.equals(r.getProcessInstanceId()))
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

    private String variableValue(UUID processInstanceId, String name) {
        VariableQuery q = new VariableQuery();
        q.setProcessInstanceId(processInstanceId);
        return queryService.findVariables(q, null).getData().stream()
            .filter(v -> name.equals(v.getName()))
            .map(ProcessVariable::getValue)
            .findFirst()
            .orElse(null);
    }

    private ActivityStatus activityStatus(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getStatus();
    }

    // ── №9 interrupting (Raxon S-033) ──────────────────────────────────────

    /**
     * Criterion 1: an interrupting timer boundary on a serviceTask host is scheduled
     * when the host is entered (boundary timer job row exists for the host activity).
     * RED on master: ServiceTaskHandler.enter never calls BoundaryScheduler, so no
     * row is created at all.
     */
    @Test
    void interrupting_boundaryTimerScheduledOnServiceTaskHost() throws Exception {
        UUID pi = deployAndStart("test-diff4-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-work");
        assertThat(work).as("guarded task parked").isNotNull();

        ActivityEntity host = activityRepository.findById(work.getId()).orElseThrow();
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> r.getActivityId().equals(host.getId()))
            .filter(r -> "timeout".equals(r.getBoundaryElementId()))
            .toList();
        assertThat(rows).as("boundary timer job scheduled for serviceTask host").hasSize(1);
    }

    /**
     * Criterion 1 (end-to-end): with no worker for the guarded job (left open, exactly
     * like the Raxon driver), the boundary fires, the host is CANCELLED, flow follows
     * the boundary path, the escape task completes with escaped=true and the instance
     * completes. Zeebe/Raxon: vars={escaped=true}, completed=true.
     */
    @Test
    void interrupting_boundaryFires_hostCancelled_escapeCompletesInstance() throws Exception {
        UUID pi = deployAndStart("test-diff4-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-work");
        assertThat(work).as("guarded task parked").isNotNull();

        // No worker for diff4-work: the job stays open, the boundary fires first.
        fireScheduledBoundary(pi, "timeout");

        // Host cancelled, escape task parked on the boundary path.
        assertThat(activityStatus(work.getId())).isEqualTo(ActivityStatus.CANCELLED);
        ServiceTask escape = byJob(serviceTasks(pi), "diff4-escape");
        assertThat(escape).as("escape task on boundary path").isNotNull();

        runtimeService.completeServiceTask(escape.getId(),
            List.of(var("escaped", "true", ProcessVariableType.BOOLEAN)));

        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("instance completed via boundary path (Raxon S-033: completed=true)").isNotNull();
        assertThat(variableValue(pi, "escaped")).isEqualTo("true");
    }

    // ── №10 non-interrupting (Raxon S-036) ──────────────────────────────────

    /**
     * Criterion 2: a non-interrupting timer boundary on a serviceTask host is scheduled
     * when the host is entered. RED on master for the same reason as interrupting.
     */
    @Test
    void nonInterrupting_boundaryTimerScheduledOnServiceTaskHost() throws Exception {
        UUID pi = deployAndStart("test-diff4-non-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-ni-work");
        assertThat(work).as("guarded task parked").isNotNull();

        ActivityEntity host = activityRepository.findById(work.getId()).orElseThrow();
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> r.getActivityId().equals(host.getId()))
            .filter(r -> "timeout".equals(r.getBoundaryElementId()))
            .toList();
        assertThat(rows).as("boundary timer job scheduled for serviceTask host").hasSize(1);
    }

    /**
     * Criterion 2 (end-to-end): the boundary fires while the guarded job is still open —
     * the host is NOT cancelled, the boundary path forks in parallel, both branches
     * complete on their own, the join converges and the shared tail completes the
     * instance. Zeebe/Raxon: vars={workResult=done, escaped=true, merged=ok},
     * completed=true.
     */
    @Test
    void nonInterrupting_boundaryFires_hostSurvives_bothBranchesJoin() throws Exception {
        UUID pi = deployAndStart("test-diff4-non-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-ni-work");
        assertThat(work).as("guarded task parked").isNotNull();

        // Boundary fires while the guarded job is still open.
        fireScheduledBoundary(pi, "timeout");

        // Host survives (NOT cancelled), escape branch parked in parallel.
        assertThat(activityStatus(work.getId())).isNotEqualTo(ActivityStatus.CANCELLED);
        ServiceTask escape = byJob(serviceTasks(pi), "diff4-ni-escape");
        assertThat(escape).as("escape task forked in parallel").isNotNull();

        // Both branches complete on their own (order: boundary branch first, like Raxon).
        runtimeService.completeServiceTask(escape.getId(),
            List.of(var("escaped", "true", ProcessVariableType.BOOLEAN)));

        // Main branch still alive — the instance must NOT be completed by the side branch.
        ProcessInstance mid = queryService.getProcessInstance(pi);
        assertThat(mid.getCompletedAt()).as("side branch alone must not complete the instance").isNull();

        runtimeService.completeServiceTask(work.getId(),
            List.of(var("workResult", "done", ProcessVariableType.STRING)));

        // Join converges, shared tail runs.
        ServiceTask after = byJob(serviceTasks(pi), "diff4-ni-after");
        assertThat(after).as("shared tail after join").isNotNull();
        runtimeService.completeServiceTask(after.getId(),
            List.of(var("merged", "ok", ProcessVariableType.STRING)));

        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("instance completed after join (Raxon S-036: completed=true)").isNotNull();
        assertThat(variableValue(pi, "workResult")).isEqualTo("done");
        assertThat(variableValue(pi, "escaped")).isEqualTo("true");
        assertThat(variableValue(pi, "merged")).isEqualTo("ok");
    }

    // ── Criterion 4: happy path (Raxon S-032/S-035) ─────────────────────────

    /**
     * Criterion 4: task completes BEFORE the timer — the boundary never fires, normal
     * flow wins. Guards the base task semantics against the fix.
     */
    @Test
    void happyPath_taskCompletesBeforeTimer_boundaryNeverFires() throws Exception {
        UUID pi = deployAndStart("test-diff4-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-work");
        assertThat(work).as("guarded task parked").isNotNull();

        runtimeService.completeServiceTask(work.getId(),
            List.of(var("workResult", "done", ProcessVariableType.STRING)));

        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("instance completed via normal path (Raxon S-032: completed=true)").isNotNull();
        assertThat(variableValue(pi, "workResult")).isEqualTo("done");
        // Boundary path never activated.
        assertThat(byJob(serviceTasks(pi), "diff4-escape")).isNull();
    }

    /**
     * Criterion 4 (non-interrupting mirror, Raxon S-035): task completes BEFORE the
     * timer — no fork happens, instance completes normally.
     */
    @Test
    void happyPath_nonInterrupting_taskCompletesBeforeTimer_noFork() throws Exception {
        UUID pi = deployAndStart("test-diff4-non-interrupting.bpmn");

        ServiceTask work = byJob(serviceTasks(pi), "diff4-ni-work");
        assertThat(work).as("guarded task parked").isNotNull();

        runtimeService.completeServiceTask(work.getId(),
            List.of(var("workResult", "done", ProcessVariableType.STRING)));

        // Main branch reaches the join and waits (no second token ever arrives) —
        // the escape task must never appear.
        assertThat(byJob(serviceTasks(pi), "diff4-ni-escape")).isNull();
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("no fork: join waits, instance stays open (Raxon S-035 MATCH semantics)").isNull();
    }
}
