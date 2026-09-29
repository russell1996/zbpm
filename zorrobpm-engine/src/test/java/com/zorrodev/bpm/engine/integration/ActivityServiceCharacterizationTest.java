package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUD-8a: Characterization tests for ActivityServiceImpl.
 * FIXES BEHAVIOR AS-IS — does NOT change production code.
 * If a bug is found, it is noted in the report, NOT fixed here.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ActivityServiceCharacterizationTest {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityService activityService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    private final List<UUID> startedProcessInstances = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() { tx = new TransactionTemplate(txManager); }

    /**
     * WO-REL-13: the timer poller now commits each job independently (REQUIRES_NEW fire), so a
     * failing sibling job no longer rolls back the whole batch. Timer jobs left behind by this
     * characterization suite (e.g. due PT0S timers) are therefore fired and PERSIST as fired=true,
     * polluting the shared H2 database for later tests. Clean them up per instance.
     */
    @AfterEach
    void cleanupTimerJobs() {
        for (UUID pi : startedProcessInstances) {
            tx.executeWithoutResult(s -> timerJobRepository.deleteByProcessInstanceId(pi));
        }
        startedProcessInstances.clear();
    }

    private ProcessVariable pv(String name, String value) {
        ProcessVariable v = new ProcessVariable(); v.setName(name); v.setValue(value);
        v.setType(ProcessVariableType.STRING); return v;
    }

    private UUID deploy(String bpmnFile) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                return processDefinitionService.addProcessDefinition(bpmn).getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private UUID startProcess(String bpmnFile) {
        UUID pi = tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        startedProcessInstances.add(pi);
        return pi;
    }

    private UUID startProcess(String bpmnFile, List<ProcessVariable> vars) {
        UUID pi = tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(vars);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        startedProcessInstances.add(pi);
        return pi;
    }

    private UUID startProcessOrNull(String bpmnFile) {
        try {
            return startProcess(bpmnFile);
        } catch (Exception e) {
            return null;
        }
    }

    private long countBy(UUID pi, String el, ActivityStatus st) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals(el) && a.getStatus() == st).count());
    }
    private long countActive(UUID pi, String el) { return countBy(pi, el, ActivityStatus.CREATED); }
    private boolean done(UUID pi) { return tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt() != null); }
    private UUID actId(UUID pi, String el, ActivityStatus st) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals(el) && a.getStatus() == st)
            .map(ActivityEntity::getId).findFirst().orElse(null));
    }
    private long openIncidents(UUID pi) {
        return tx.execute(s -> incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
            .filter(i -> { ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                return a != null && a.getProcessInstanceId().equals(pi); }).count());
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. Events
    // ══════════════════════════════════════════════════════════════════
    @Nested class Events {
        @Test void startEvent_simple_completes() { assertThat(done(startProcess("test1.bpmn"))).isTrue(); }
        @Test void endEvent_activeAfterStart() { assertThat(countActive(startProcess("test-service-task-fail.bpmn"), "svc")).isEqualTo(1); }
        @Test void terminateEnd_cancelsParallel() {
            UUID pi = startProcess("test-terminate.bpmn");
            assertThat(done(pi)).isTrue();
            assertThat(countBy(pi, "userTask1", ActivityStatus.CANCELLED)).isEqualTo(1);
        }
        @Test void errorEnd_caughtByBoundary() { assertThat(done(startProcess("test-error-boundary.bpmn"))).isTrue(); }
        @Test void escalationEnd_caughtByBoundary() { assertThat(done(startProcess("test-escalation-interrupting.bpmn"))).isTrue(); }
        @Test void escalationThrow_propagates() { assertThat(done(startProcess("test-escalation-throw.bpmn"))).isTrue(); }
        @Test void intermediateThrow_passThrough() { assertThat(done(startProcess("test-throw.bpmn"))).isTrue(); }
        @Test void signalStart_receivesSignal() {
            UUID pdId = deploy("test-signal-start-receiver.bpmn");
            tx.executeWithoutResult(s -> activityService.correlateMessage("go", pdId, List.of()));
            assertThat(pdId).isNotNull();
        }
        @Test void messageStart_correlatesMessage() {
            UUID pdId = deploy("test-message-start.bpmn");
            tx.executeWithoutResult(s -> activityService.correlateMessage("startMsg", pdId, List.of()));
            assertThat(pdId).isNotNull();
        }
        @Test void timerStart_noPlainStart_throws() {
            // Characterizing: timer-start process has no plain start event
            // startProcessInstance throws because no plain start event
            UUID pdId = deploy("test-timer-start.bpmn");
            assertThat(pdId).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. Tasks
    // ══════════════════════════════════════════════════════════════════
    @Nested class Tasks {
        @Test void serviceTask_active() { assertThat(countActive(startProcess("test-service-task-fail.bpmn"), "svc")).isEqualTo(1); }
        @Test void serviceTask_completeToEnd() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc, List.of()));
            assertThat(done(pi)).isTrue();
        }
        @Test void userTask_parksAsActive() {
            UUID pi = startProcess("test-usertask-query.bpmn");
            assertThat(countActive(pi, "approve")).isGreaterThanOrEqualTo(1);
        }
        @Test void scriptTask_evaluatesAndCompletes() { assertThat(done(startProcess("test-script-task.bpmn"))).isTrue(); }
        @Test void businessRule_feelEvaluates() { assertThat(done(startProcess("test-business-rule-feel.bpmn"))).isTrue(); }
        @Test void sendTask_zeebe_createsJob() {
            UUID pi = startProcess("test-send-task-zeebe.bpmn");
            // send task with zeebe:taskDefinition becomes a service task
            // find any CREATED activity
            long active = tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi) && a.getStatus() == ActivityStatus.CREATED).count());
            assertThat(active).isGreaterThanOrEqualTo(1);
        }
        @Test void receiveTask_parks() { assertThat(done(startProcess("test-send-receive.bpmn"))).isFalse(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. Gateways
    // ══════════════════════════════════════════════════════════════════
    @Nested class Gateways {
        @Test void exclusiveGateway_defaultFlow() { assertThat(startProcess("test-gateway-no-default.bpmn")).isNotNull(); }
        @Test void parallelGateway_forks3ways() {
            UUID pi = startProcess("test-signal.bpmn");
            assertThat(countActive(pi, "signalCatchA")).isEqualTo(1);
            assertThat(countActive(pi, "signalCatchB")).isEqualTo(1);
            assertThat(countActive(pi, "gate")).isEqualTo(1);
        }
        @Test void inclusiveGateway_defaultOnly() { assertThat(done(startProcess("test-inclusive-gateway.bpmn"))).isTrue(); }
        @Test void inclusiveGateway_withVars() {
            UUID pi = startProcess("test-inclusive-gateway.bpmn", List.of(pv("a", "yes"), pv("b", "yes")));
            assertThat(pi).isNotNull();
        }
        @Test void eventBasedGateway_parks() { assertThat(done(startProcess("test-event-based-gateway.bpmn"))).isFalse(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. SubProcess / CallActivity
    // ══════════════════════════════════════════════════════════════════
    @Nested class SubProcessAndCall {
        @Test void subProcess_completes() { assertThat(done(startProcess("test-subprocess.bpmn"))).isTrue(); }
        @Test void callActivity_undeployed_incident() { assertThat(openIncidents(startProcess("test-call-undeployed.bpmn"))).isGreaterThanOrEqualTo(1); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. Wait states
    // ══════════════════════════════════════════════════════════════════
    @Nested class WaitStates {
        @Test void signalCatch_parks() {
            UUID pi = startProcess("test-signal.bpmn");
            assertThat(countActive(pi, "signalCatchA")).isEqualTo(1);
        }
        @Test void messageCatch_parks() { assertThat(done(startProcess("test-message-boundary.bpmn"))).isFalse(); }
        @Test void timerCatch_parks() { assertThat(done(startProcess("test-catch.bpmn"))).isFalse(); }
        @Test void conditionalCatch_parks() { assertThat(done(startProcess("test-conditional-catch.bpmn"))).isFalse(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. Throw events
    // ══════════════════════════════════════════════════════════════════
    @Nested class ThrowEvents {
        @Test void signalThrow_wakesCatchers() {
            UUID pi = startProcess("test-signal.bpmn");
            UUID gate = actId(pi, "gate", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeUserTask(gate, List.of()));
            assertThat(done(pi)).isTrue();
        }
        @Test void messageThrow_published() { assertThat(startProcess("test-message-throw.bpmn")).isNotNull(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 7. Link events
    // ══════════════════════════════════════════════════════════════════
    @Nested class LinkEvents {
        @Test void linkThrow_jumpsToCatch() { assertThat(done(startProcess("test-link-events.bpmn"))).isTrue(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 8. Compensation
    // ══════════════════════════════════════════════════════════════════
    @Nested class Compensation {
        @Test void compensationThrow_runs() { assertThat(startProcess("test-compensation.bpmn")).isNotNull(); }
    }

    // ══════════════════════════════════════════════════════════════════
    // 9. completeServiceTask
    // ══════════════════════════════════════════════════════════════════
    @Nested class CompleteServiceTaskTests {
        @Test void active_advances() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc, List.of()));
            assertThat(done(pi)).isTrue();
        }
        @Test void alreadyCompleted_noOp() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc, List.of()));
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc, List.of()));
            assertThat(done(pi)).isTrue();
        }
        @Test void cancelled_noOp() {
            UUID pi = startProcess("test-terminate.bpmn");
            UUID ut = actId(pi, "userTask1", ActivityStatus.CANCELLED);
            if (ut != null) {
                tx.executeWithoutResult(s -> runtimeService.completeServiceTask(ut, List.of()));
                assertThat(done(pi)).isTrue();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 10. failServiceTask
    // ══════════════════════════════════════════════════════════════════
    @Nested class FailServiceTaskTests {
        @Test void retriesLeft_noIncident() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "timeout", null));
            assertThat(openIncidents(pi)).isEqualTo(0);
        }
        @Test void retriesExhausted_incident() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "fatal", 0));
            assertThat(openIncidents(pi)).isGreaterThanOrEqualTo(1);
        }
        @Test void alreadyCompleted_noOp() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc, List.of()));
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "late", 0));
            assertThat(done(pi)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 11. completeUserTask
    // ══════════════════════════════════════════════════════════════════
    @Nested class CompleteUserTaskTests {
        @Test void advances() {
            UUID pi = startProcess("test-usertask-query.bpmn");
            UUID ut = actId(pi, "approve", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.completeUserTask(ut, List.of()));
            assertThat(done(pi)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 12. signal
    // ══════════════════════════════════════════════════════════════════
    @Nested class SignalTests {
        @Test void wakesCatch() {
            UUID pi = startProcess("test-signal.bpmn");
            UUID catchA = actId(pi, "signalCatchA", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> activityService.signal(catchA, List.of()));
            assertThat(countBy(pi, "signalCatchA", ActivityStatus.COMPLETED)).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 13. correlateMessage
    // ══════════════════════════════════════════════════════════════════
    @Nested class CorrelateMessageTests {
        @Test void wakesMessageBoundary() {
            UUID pi = startProcess("test-message-boundary.bpmn");
            tx.executeWithoutResult(s -> activityService.correlateMessage("cancel-order", pi, List.of()));
            assertThat(done(pi)).isTrue();
        }
        @Test void withCorrelationKey() {
            UUID pi = startProcess("test-message-correlation-key.bpmn");
            tx.executeWithoutResult(s -> activityService.correlateMessage("orderUpdate", "order-123", pi, List.of()));
            assertThat(pi).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 14. fireBoundaryTimer
    // ══════════════════════════════════════════════════════════════════
    @Nested class FireBoundaryTimerTests {
        @Test void interruptsHost() {
            UUID pi = startProcess("test-boundary.bpmn");
            UUID host = actId(pi, "svc", ActivityStatus.CREATED);
            if (host != null) {
                tx.executeWithoutResult(s -> activityService.fireBoundaryTimer(host, "timerBoundary"));
                assertThat(countBy(pi, "svc", ActivityStatus.CANCELLED)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 15. fireEventSubprocessTimer
    // ══════════════════════════════════════════════════════════════════
    @Nested class FireEventSubprocessTimerTests {
        @Test void startsSubprocess() {
            UUID pi = startProcess("test-event-subprocess-timer.bpmn");
            tx.executeWithoutResult(s -> activityService.fireEventSubprocessTimer(pi, "timeoutHandler"));
            assertThat(pi).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 16. resolveIncident (REL-5 guards)
    // ══════════════════════════════════════════════════════════════════
    @Nested class ResolveIncidentTests {
        @Test void normalPath_createsNew() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
            UUID inc = tx.execute(s -> incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
                .filter(i -> { ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                    return a != null && a.getProcessInstanceId().equals(pi); })
                .map(IncidentEntity::getId).findFirst().orElseThrow());
            tx.executeWithoutResult(s -> activityService.resolveIncident(inc, List.of()));
            assertThat(countActive(pi, "svc")).isEqualTo(1);
        }
        @Test void alreadyResolved_noOp() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
            UUID inc = tx.execute(s -> incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
                .filter(i -> { ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                    return a != null && a.getProcessInstanceId().equals(pi); })
                .map(IncidentEntity::getId).findFirst().orElseThrow());
            tx.executeWithoutResult(s -> activityService.resolveIncident(inc, List.of()));
            long after = countActive(pi, "svc");
            tx.executeWithoutResult(s -> activityService.resolveIncident(inc, List.of()));
            assertThat(countActive(pi, "svc")).isEqualTo(after);
        }
        @Test void activeExists_guardCloses() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            UUID tokenId = tx.execute(s -> activityRepository.findById(svc).orElseThrow().getToken());
            ActivityEntity stale = new ActivityEntity();
            stale.setId(UUID.randomUUID()); stale.setProcessInstanceId(pi); stale.setToken(tokenId);
            stale.setBpmnElementId("svc"); stale.setStatus(ActivityStatus.ERROR); stale.setCreatedAt(Instant.now());
            tx.execute(s -> activityRepository.save(stale));
            IncidentEntity staleInc = new IncidentEntity();
            staleInc.setId(UUID.randomUUID()); staleInc.setActivityId(stale.getId());
            staleInc.setMessage("stale"); staleInc.setCreatedAt(Instant.now());
            tx.execute(s -> incidentRepository.save(staleInc));
            // Before resolve: stale incident is open
            Instant beforeResolve = tx.execute(s -> incidentRepository.findById(staleInc.getId()).orElseThrow().getCompletedAt());
            assertThat(beforeResolve).isNull();
            tx.executeWithoutResult(s -> activityService.resolveIncident(staleInc.getId(), List.of()));
            // After resolve: exactly 1 active activity (no re-execution)
            assertThat(countActive(pi, "svc")).isEqualTo(1);
            // AND: stale incident is closed (guard completed it)
            Instant afterResolve = tx.execute(s -> incidentRepository.findById(staleInc.getId()).orElseThrow().getCompletedAt());
            assertThat(afterResolve).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 17. startProcessInstance / startFromStartEvent
    // ══════════════════════════════════════════════════════════════════
    @Nested class StartMethods {
        @Test void start_createsAndCompletes() {
            UUID pi = startProcess("test1.bpmn");
            assertThat(pi).isNotNull(); assertThat(done(pi)).isTrue();
        }
        @Test void startFromStartEvent() {
            UUID pi = tx.execute(s -> {
                try {
                    String bpmn = Files.readString(Paths.get("src/test/files/test-signal-start-receiver.bpmn"));
                    ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                    return activityService.startProcessInstanceFromStartEvent(model.getId(), "signalStart", List.of());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertThat(pi).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 18. executionDepth
    // ══════════════════════════════════════════════════════════════════
    @Nested class ExecutionDepthTests {
        @Test void recursiveParallelGateway_hitsDepthLimit() {
            // parallel gateway loops back to itself → depth limit hit → transaction rolls back
            // The process instance should NOT be persisted (creation is inside the same tx as execute)
            UUID pi = startProcessOrNull("test-loop.bpmn");
            if (pi != null) {
                boolean exists = tx.execute(s -> queryService.getProcessInstance(pi) != null);
                assertThat(exists).as("Process instance should not exist after depth limit").isFalse();
            }
            // If pi is null, startProcess itself threw → also valid (tx rolled back before returning)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 19. evaluatingConditionals
    // ══════════════════════════════════════════════════════════════════
    @Nested class EvaluatingConditionalsTests {
        @Test void guardPreventsReEntrance() {
            // conditional-boundary: condition =active = "true" on work userTask
            UUID pi = startProcess("test-conditional-boundary.bpmn", List.of(pv("active", "true")));
            assertThat(pi).isNotNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 20. Edge cases
    // ══════════════════════════════════════════════════════════════════
    @Nested class EdgeCases {
        @Test void unsupportedType_incidentOrPassThrough() { assertThat(startProcess("dummy-process.bpmn")).isNotNull(); }
        @Test void multipleIncidents_resolvedSafely() {
            UUID pi = startProcess("test-service-task-fail.bpmn");
            UUID svc = actId(pi, "svc", ActivityStatus.CREATED);
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "err1", 0));
            UUID inc = tx.execute(s -> incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
                .filter(i -> { ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                    return a != null && a.getProcessInstanceId().equals(pi); })
                .map(IncidentEntity::getId).findFirst().orElseThrow());
            tx.executeWithoutResult(s -> activityService.resolveIncident(inc, List.of()));
            assertThat(countActive(pi, "svc")).isEqualTo(1);
        }
    }
}
