package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-19: {@code resolveIncident} confused the completion of a PREVIOUS visit with the
 * execution of the CURRENT one (N01, repeat audit 2026-09-23).
 *
 * <p>The old idempotency check ({@code hasCompletedActivityOnTokenAndElement}) matched ANY
 * COMPLETED activity for the (token, element) pair. On a BPMN loop back to an already-executed
 * element the token is reused, so resolving the NEW incident found the OLD visit's COMPLETED row,
 * closed the incident as "resolved" and never re-executed the failed attempt — the process stayed
 * parked while looking resolved. The fix scopes the check to the current visit lineage (the
 * incident's own activity, or a replacement created at/after it) and re-reads incident/activity
 * AFTER the process-instance lock.
 */
public class IncidentResolveVisitConfusionPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // Same residue wipe as FeelTimerPgIT (children before parents): this class commits
        // instances + incidents, later PgIT classes in the shared-PG suite must not see them.
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'test-%'");
    }

    private ProcessVariable route(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("route");
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private UUID deployAndStart(String bpmnFile, List<ProcessVariable> variables) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(variables);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private ActivityEntity activeSvc(UUID pi) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());
    }

    private long countByTokenAndElement(UUID token, String elementId) {
        return tx.execute(s -> (long) activityRepository.findByTokenAndBpmnElementId(token, elementId).size());
    }

    private long countActiveByTokenAndElement(UUID token, String elementId) {
        return tx.execute(s -> (long) activityRepository
            .findByTokenAndBpmnElementIdAndStatusIn(token, elementId,
                List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).size());
    }

    @Test
    void loopRevisit_resolveReexecutesCurrentVisitDespitePreviousCompletion() throws Exception {
        // Visit 1 completes with route="again" -> gateway loops back to svc on the SAME token.
        UUID pi = deployAndStart("test-eng-19-loop-revisit.bpmn", List.of(route("again")));

        ActivityEntity svc1 = activeSvc(pi);
        UUID token = svc1.getToken();
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc1.getId(), List.of()));

        // Visit 2 is a NEW activity on the SAME token (loop premise — fail loudly if not).
        ActivityEntity svc2 = activeSvc(pi);
        assertThat(svc2.getId()).isNotEqualTo(svc1.getId());
        assertThat(svc2.getToken()).as("loop must reuse the token (scenario premise)").isEqualTo(token);
        ActivityStatus svc1Status = tx.execute(s -> activityRepository.findById(svc1.getId()).orElseThrow().getStatus());
        assertThat(svc1Status).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(svc2.getCreatedAt())
            .as("visit-2 activity must be created after visit-1 (lineage premise)")
            .isAfter(svc1.getCreatedAt());

        // Visit 2 fails -> incident parked on the visit-2 activity.
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc2.getId(), "boom", 0));
        UUID incidentId = tx.execute(s -> incidentRepository
            .findByActivityIdInAndCompletedAtIsNull(List.of(svc2.getId())).get(0).getId());

        // Resolve: the old code found visit-1's COMPLETED row, closed the incident and created
        // NOTHING (process parked, "resolved"). The fix must re-execute visit 2.
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        // Incident closed AND exactly one fresh active activity for the current visit.
        IncidentEntity after = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(after.getCompletedAt()).as("incident must be closed").isNotNull();
        assertThat(countByTokenAndElement(token, "svc"))
            .as("svc1 COMPLETED + svc2 ERROR/CANCELLED + one re-executed visit-2 activity")
            .isEqualTo(3);
        assertThat(countActiveByTokenAndElement(token, "svc"))
            .as("resolve must re-execute the CURRENT (second) visit — this is the WO-ENG-19 discriminator")
            .isEqualTo(1);
        ActivityEntity svc3 = activeSvc(pi);
        assertThat(svc3.getId()).isNotIn(svc1.getId(), svc2.getId());
        ActivityStatus svc2Status = tx.execute(s -> activityRepository.findById(svc2.getId()).orElseThrow().getStatus());
        assertThat(svc2Status)
            .as("parked visit-2 activity must be cancelled").isEqualTo(ActivityStatus.CANCELLED);

        // End-to-end: the re-executed visit really runs — complete with route="done" -> end.
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc3.getId(), List.of(route("done"))));
        var completedAt = tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt)
            .as("process must finish after the re-executed visit completes").isNotNull();
        long endEvents = tx.execute(s -> (long) activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)
            .count());
        assertThat(endEvents).as("exactly one end event reached").isEqualTo(1);
    }

    @Test
    void concurrentResolve_sameIncident_singleReexecution() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn", List.of());
        ActivityEntity svc1 = activeSvc(pi);
        UUID token = svc1.getToken();

        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1.getId(), "boom", 0));
        UUID incidentId = tx.execute(s -> incidentRepository
            .findByActivityIdInAndCompletedAtIsNull(List.of(svc1.getId())).get(0).getId());

        // Two real threads resolve the SAME incident at once (V6). Each thread calls the Spring
        // bean directly (no shared test transaction) so each runs in its own transaction,
        // serialised only by the process-instance lock inside resolveIncident.
        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> resolveThroughGate(gate, incidentId, failure));
            Future<?> f2 = pool.submit(() -> resolveThroughGate(gate, incidentId, failure));
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).as("no resolve thread may fail").isNull();

        // Exactly ONE re-execution: the loser must see the winner's commit after the lock.
        IncidentEntity after = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(after.getCompletedAt()).as("incident must be closed").isNotNull();
        assertThat(countByTokenAndElement(token, "svc"))
            .as("exactly one re-executed activity — concurrent resolve must not duplicate")
            .isEqualTo(2);
        assertThat(countActiveByTokenAndElement(token, "svc")).isEqualTo(1);
    }

    private void resolveThroughGate(CyclicBarrier gate, UUID incidentId, AtomicReference<Throwable> failure) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            runtimeService.resolveIncident(incidentId, List.of());
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
