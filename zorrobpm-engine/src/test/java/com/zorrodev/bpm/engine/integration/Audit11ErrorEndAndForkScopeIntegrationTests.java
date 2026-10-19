package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-11 — F-01 (unhandled ERROR end event parks the instance forever) and
 * F-09 (error boundary of the enclosing subprocess lost after a parallel fork).
 *
 * <p>Parity target measured LIVE on Zeebe 8.5.0 (oracle stand {@code zbpm-cmp-zeebe},
 * scenarios {@code docs/analysis/c8-oracle/scenarios/a11-*}):
 * <ul>
 *   <li>F-01: an error end event nobody catches raises an INCIDENT (broker counters
 *       {@code zeebe_incident_events_total{action="created"}} +1, {@code
 *       zeebe_pending_incidents_total} +1) and the instance stays ALIVE — the flow stops
 *       at the error end event, it is not silently completed.</li>
 *   <li>F-09: an error thrown in a parallel branch INSIDE an embedded subprocess IS caught
 *       by the error boundary on that subprocess: no incident, the boundary's outgoing flow
 *       is taken (job {@code a11AfterBoundary} created) and the sibling branch's job is
 *       cancelled (not activatable).</li>
 * </ul>
 *
 * <p>Each step runs in its OWN transaction (TransactionTemplate) — the same shape as the
 * real per-HTTP-request transaction, so committed state is visible across steps and the
 * global leak-invariant extension (WO-OPS-26) really inspects the finished instance.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Audit11ErrorEndAndForkScopeIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID start(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        return tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
    }

    private List<ActivityEntity> activities(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .toList());
    }

    private List<IncidentEntity> openIncidents(UUID pi) {
        return tx.execute(s -> incidentRepository.findAll().stream()
            .filter(i -> i.getCompletedAt() == null)
            .filter(i -> activityRepository.findById(i.getActivityId())
                .map(a -> pi.equals(a.getProcessInstanceId()))
                .orElse(false))
            .toList());
    }

    private ProcessInstance instance(UUID pi) {
        return tx.execute(s -> queryService.getProcessInstance(pi));
    }

    // ── F-01 ────────────────────────────────────────────────────────────────────────────

    /**
     * F-01: an error end event that nobody catches must PARK the element in ERROR with an
     * OPEN incident — not complete the element and leave the instance running with no
     * active activity and no way out.
     */
    @Test
    void unhandledErrorEnd_parksElementInErrorWithOpenIncident() throws Exception {
        UUID pi = start("test-audit11-unhandled-error-end.bpmn");

        List<ActivityEntity> errEnd = activities(pi, "errEnd");
        assertThat(errEnd).hasSize(1);
        assertThat(errEnd.get(0).getStatus())
            .as("F-01: необработанный error end должен остаться в ERROR, а не COMPLETED")
            .isEqualTo(ActivityStatus.ERROR);

        List<IncidentEntity> incidents = openIncidents(pi);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getActivityId()).isEqualTo(errEnd.get(0).getId());
        assertThat(incidents.get(0).getMessage()).contains("Unhandled BPMN error");

        // the instance is parked, NOT silently finished: the operator still has a handle
        assertThat(instance(pi).getCompletedAt()).isNull();
    }

    /**
     * F-01: resolving the incident must RE-EXECUTE the element (Zeebe: retry the failed
     * element), not close the incident on an already-COMPLETED row and leave the instance
     * hanging forever with nothing to act on.
     */
    @Test
    void unhandledErrorEnd_resolveReExecutesElementInsteadOfSilentlyClosing() throws Exception {
        UUID pi = start("test-audit11-unhandled-error-end.bpmn");
        List<IncidentEntity> incidents = openIncidents(pi);
        assertThat(incidents).hasSize(1);
        UUID incidentId = incidents.get(0).getId();

        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        // the incident we resolved is closed ...
        java.time.Instant closedAt = tx.execute(
            s -> incidentRepository.findById(incidentId).orElseThrow().getCompletedAt());
        assertThat(closedAt).as("resolve закрывает инцидент").isNotNull();
        // ... and the element was re-executed (a second attempt exists)
        List<ActivityEntity> errEnd = activities(pi, "errEnd");
        assertThat(errEnd).as("resolve переисполняет элемент, а не тихо закрывает инцидент").hasSize(2);
        // the re-execution threw the very same unhandled error again -> parked again, visibly
        assertThat(errEnd.get(1).getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(openIncidents(pi)).hasSize(1);
        // and the instance is still alive (not silently completed behind the operator's back)
        assertThat(instance(pi).getCompletedAt()).isNull();
    }

    // ── F-09 ────────────────────────────────────────────────────────────────────────────

    /**
     * F-09: an error thrown in one branch of a parallel gateway INSIDE an embedded
     * subprocess must be caught by the error boundary attached to that subprocess —
     * the fork token must not lose the scope of the enclosing subprocess.
     */
    @Test
    void errorInsideParallelBranch_isCaughtByEnclosingSubprocessBoundary() throws Exception {
        UUID pi = start("test-audit11-fork-scope-error-boundary.bpmn");

        assertThat(openIncidents(pi))
            .as("F-09: граница объемлющего подпроцесса обязана поймать ошибку из ветки форка")
            .isEmpty();

        // the boundary's outgoing flow was taken
        List<ActivityEntity> afterBoundary = activities(pi, "afterBoundary");
        assertThat(afterBoundary).hasSize(1);
        assertThat(afterBoundary.get(0).getStatus()).isEqualTo(ActivityStatus.CREATED);

        // the interrupted scope is cancelled: the container and the sibling branch
        List<ActivityEntity> sub = activities(pi, "sub1");
        assertThat(sub).hasSize(1);
        assertThat(sub.get(0).getStatus()).isEqualTo(ActivityStatus.CANCELLED);

        List<ActivityEntity> sibling = activities(pi, "siblingTask");
        assertThat(sibling).hasSize(1);
        assertThat(sibling.get(0).getStatus())
            .as("прерывающая граница гасит ВСЕ ветви отменённого скоупа")
            .isEqualTo(ActivityStatus.CANCELLED);

        // the throwing element itself is not left alive either
        List<ActivityEntity> errEnd = activities(pi, "errEnd");
        assertThat(errEnd).hasSize(1);
        assertThat(errEnd.get(0).getStatus()).isEqualTo(ActivityStatus.CANCELLED);

        // finish the boundary path: the worker completes the service task -> instance ends
        UUID afterBoundaryId = afterBoundary.get(0).getId();
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(afterBoundaryId, List.of()));

        ProcessInstance piRow = instance(pi);
        assertThat(piRow.getCompletedAt())
            .as("после границы инстанс доходит до конца")
            .isNotNull();
    }
}
