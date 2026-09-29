package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.handler.IncidentService;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-23: {@code raiseIncident} picks the "last" activity as
 * {@code activities.get(size() - 1)}, assuming creation order — but
 * {@code findByTokenAndBpmnElementId} is a derived query WITHOUT {@code ORDER BY},
 * so PostgreSQL returns rows in heap (TID) order. After retention-like churn
 * (DELETE + VACUUM) the live visit can sit physically BEFORE the past COMPLETED
 * visit, and the incident binds to (and corrupts) the past visit while the live
 * activity stays parked — the N01 outcome via a different path.
 */
public class IncidentLoopVisitOrderPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private IncidentService incidentService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
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

    private BpmnElementModel svcModel() {
        BpmnElementModel svc = new BpmnElementModel();
        svc.setId("svc");
        svc.setType(BpmnElementType.SERVICE_TASK);
        return svc;
    }

    private UUID deployAndStart() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test-eng-23-svc-then-wait.bpmn"));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(List.of());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Activity activeSvc(UUID pi) {
        UUID token = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi)
                && a.getBpmnElementId().equals("svc")
                && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getToken());
        List<Activity> found = tx.execute(s ->
            dbService.getActivitiesByTokenAndBpmnElementId(token, "svc"));
        return found.stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED).findFirst().orElseThrow();
    }

    @Test
    void pastVisitPhysicallyLast_incidentBindsToLiveVisit() {
        UUID pi = deployAndStart();

        // A0 completes -> flow parks at the user task, instance stays LIVE on the same token.
        Activity a0 = activeSvc(pi);
        UUID token = a0.getToken();
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(a0.getId(), List.of()));
        UUID waitToken = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("wait"))
            .findFirst().orElseThrow().getToken());
        assertThat(waitToken)
            .as("linear flow must keep one token (scenario premise)")
            .isEqualTo(token);

        // Churn: 5000 filler rows in the middle of the heap, then retention-like wipe.
        Instant fillerAt = Instant.now();
        tx.executeWithoutResult(s -> {
            List<Object[]> batch = new ArrayList<>(5000);
            for (int i = 0; i < 5000; i++) {
                batch.add(new Object[]{UUID.randomUUID(), pi, token, "eng23-filler",
                    Timestamp.from(fillerAt), "SERVICE_TASK", "CREATED"});
            }
            jdbc.batchUpdate(
                "INSERT INTO activities (id, process_instance_id, token, bpmn_element_id,"
                    + " created_at, type, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batch);
        });

        // Visit 1 lands at the heap end, then completes (HOT or not — still at the end).
        UUID visit1 = tx.execute(s -> dbService.createActivity(pi, token, svcModel()));
        tx.executeWithoutResult(s -> dbService.completeActivity(visit1));

        // Retention-like churn frees the early pages; VACUUM makes them reusable.
        // (JdbcTemplate single statements run autocommitted — VACUUM is legal here.)
        jdbc.update("DELETE FROM activities WHERE bpmn_element_id = 'eng23-filler'");
        jdbc.execute("VACUUM ANALYZE activities");

        // Visit 2 (live) is INSERTed into the freed early space — physically BEFORE visit 1.
        UUID visit2 = tx.execute(s -> dbService.createActivity(pi, token, svcModel()));
        Instant visit2CreatedAt =
            tx.execute(s -> dbService.getActivity(visit2).getCreatedAt());

        // Scenario premise: the unordered query really returns the PAST visit last.
        List<UUID> loopOrder = tx.execute(s -> dbService
            .getActivitiesByTokenAndBpmnElementId(token, "svc").stream()
            .map(Activity::getId)
            .filter(id -> id.equals(visit1) || id.equals(visit2))
            .toList());
        assertThat(loopOrder).containsExactly(visit2, visit1);
        assertThat(loopOrder.get(loopOrder.size() - 1))
            .as("premise: heap order puts the past COMPLETED visit last (else the bug cannot trigger)")
            .isEqualTo(visit1);

        // Visit 2 fails -> raiseIncident through the real bean.
        tx.executeWithoutResult(s ->
            incidentService.raiseIncident(pi, token, svcModel(), new RuntimeException("boom")));

        List<IncidentEntity> open = tx.execute(s -> incidentRepository
            .findByActivityIdInAndCompletedAtIsNull(List.of(visit1, visit2)));
        assertThat(open).as("exactly one incident parked").hasSize(1);
        UUID incidentId = open.get(0).getId();
        assertThat(open.get(0).getActivityId())
            .as("WO-ENG-23 discriminator: incident must bind the LIVE visit, not the past one")
            .isEqualTo(visit2);
        ActivityStatus visit1Status =
            tx.execute(s -> dbService.getActivity(visit1).getStatus());
        assertThat(visit1Status)
            .as("past visit must stay COMPLETED (history must not be corrupted)")
            .isEqualTo(ActivityStatus.COMPLETED);
        ActivityStatus visit2Status =
            tx.execute(s -> dbService.getActivity(visit2).getStatus());
        assertThat(visit2Status)
            .as("live visit must be parked as ERROR")
            .isEqualTo(ActivityStatus.ERROR);

        // Criterion 3: resolve re-executes the live visit (exactly once), it really runs.
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        Instant incidentClosedAt =
            tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow().getCompletedAt());
        assertThat(incidentClosedAt).as("incident must be closed").isNotNull();
        ActivityStatus visit2AfterResolve =
            tx.execute(s -> dbService.getActivity(visit2).getStatus());
        assertThat(visit2AfterResolve)
            .as("parked live visit must be cancelled, not left IN_PROGRESS forever")
            .isEqualTo(ActivityStatus.CANCELLED);
        Activity reexecuted = tx.execute(s -> dbService.getActivitiesByTokenAndBpmnElementId(token, "svc")
            .stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                && !a.getId().equals(a0.getId())
                && !a.getId().equals(visit1)
                && !a.getId().equals(visit2))
            .findFirst().orElseThrow());
        assertThat(reexecuted.getCreatedAt())
            .as("re-executed activity must be newer than the parked visit")
            .isAfter(visit2CreatedAt);
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(reexecuted.getId(), List.of()));
        ActivityStatus reexecutedStatus =
            tx.execute(s -> dbService.getActivity(reexecuted.getId()).getStatus());
        assertThat(reexecutedStatus)
            .as("re-executed element must really run to completion")
            .isEqualTo(ActivityStatus.COMPLETED);
    }

    @Test
    void errorActivity_completedRow_isNoOp() {
        UUID pi = deployAndStart();
        Activity a0 = activeSvc(pi);

        tx.executeWithoutResult(s -> dbService.completeActivity(a0.getId()));
        Instant completedAt =
            tx.execute(s -> dbService.getActivity(a0.getId()).getCompletedAt());

        tx.executeWithoutResult(s -> dbService.errorActivity(a0.getId()));
        ActivityStatus afterError =
            tx.execute(s -> dbService.getActivity(a0.getId()).getStatus());
        assertThat(afterError)
            .as("errorActivity on a COMPLETED row must be a no-op, not silent corruption to ERROR")
            .isEqualTo(ActivityStatus.COMPLETED);
        Instant completedAtAfter =
            tx.execute(s -> dbService.getActivity(a0.getId()).getCompletedAt());
        assertThat(completedAtAfter)
            .as("completedAt must be untouched by the no-op")
            .isEqualTo(completedAt);

        // Positive control: the normal path (active -> ERROR) still works.
        UUID live = tx.execute(s -> dbService.createActivity(pi, a0.getToken(), svcModel()));
        tx.executeWithoutResult(s -> dbService.errorActivity(live));
        ActivityStatus liveStatus =
            tx.execute(s -> dbService.getActivity(live).getStatus());
        assertThat(liveStatus)
            .as("errorActivity on an active row must still park it as ERROR")
            .isEqualTo(ActivityStatus.ERROR);
    }
}
