package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-5: resolveIncident guards against double-execution.
 *
 * Each step runs in its OWN transaction via {@link TransactionTemplate} — mirroring the real
 * per-HTTP-request transaction so committed state is visible across steps. Assertions are scoped
 * to this test's own process instance, so the shared H2 schema is fine.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentResolveDoubleExecutionIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID activeServiceTask(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();
    }

    private IncidentQuery openIncidents(UUID pi) {
        IncidentQuery q = new IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        return q;
    }

    private long countActiveByElement(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .count();
    }

    private IncidentEntity createIncidentOnActivity(ActivityEntity activity) {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setActivityId(activity.getId());
        incident.setMessage("test incident");
        incident.setCreatedAt(Instant.now());
        return incidentRepository.save(incident);
    }

    // ── criterion #4: normal path — single incident → resolve → 1 new activity, old CANCELLED ──

    @Test
    void normalPath_singleIncident_resolveCreatesExactlyOneActivity() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        UUID svc1 = tx.execute(s -> activeServiceTask(pi));

        // fail with retries=0 → incident
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1, "boom", 0));
        UUID incidentId = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().get(0).getId());

        // resolve → 1 new active, old cancelled
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        assertThat(countActiveByElement(pi, "svc")).isEqualTo(1);
        UUID svc2 = tx.execute(s -> activeServiceTask(pi));
        assertThat(svc2).isNotEqualTo(svc1);
        ActivityStatus svc1Status = tx.execute(s -> activityRepository.findById(svc1).orElseThrow().getStatus());
        assertThat(svc1Status).isEqualTo(ActivityStatus.CANCELLED);

        // completing the fresh task finishes the process
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc2, List.of()));
        Instant completedAfterFresh = tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAfterFresh).isNotNull();
    }

    // ── criterion #1: 2 open incidents on same (token,element) → resolve both → 1 live activity ──

    @Test
    void twoIncidentsOnSameElement_resolveBothProducesOnlyOneActive() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        // Get the active service task and its token
        ActivityEntity svc1 = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());

        UUID tokenId = svc1.getToken();

        // Create a second ERROR activity on the same (token, element) with its own incident
        ActivityEntity svc2Error = new ActivityEntity();
        svc2Error.setId(UUID.randomUUID());
        svc2Error.setProcessInstanceId(pi);
        svc2Error.setToken(tokenId);
        svc2Error.setBpmnElementId("svc");
        svc2Error.setStatus(ActivityStatus.ERROR);
        svc2Error.setCreatedAt(Instant.now());
        tx.execute(s -> activityRepository.save(svc2Error));

        IncidentEntity inc2 = tx.execute(s -> createIncidentOnActivity(svc2Error));

        // Fail the first activity → incident
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1.getId(), "boom", 0));
        UUID inc1Id = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().get(0).getId());

        // Resolve both incidents — second resolve should be guarded (active already exists from first resolve)
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(inc1Id, List.of()));
        assertThat(countActiveByElement(pi, "svc")).isEqualTo(1);

        tx.executeWithoutResult(s -> runtimeService.resolveIncident(inc2.getId(), List.of()));
        // Still exactly 1 active — the guard prevented double-execution
        assertThat(countActiveByElement(pi, "svc")).as("must remain exactly 1 active activity").isEqualTo(1);
    }

    // ── criterion #2: resolve already-resolved incident → no-op ──

    @Test
    void resolveAlreadyResolvedIncident_noOp() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        UUID svc1 = tx.execute(s -> activeServiceTask(pi));
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1, "boom", 0));
        UUID incidentId = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().get(0).getId());

        // First resolve → succeeds, creates new activity
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        assertThat(countActiveByElement(pi, "svc")).isEqualTo(1);

        // Second resolve of the SAME incident → idempotent no-op
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        assertThat(countActiveByElement(pi, "svc"))
            .as("idempotent resolve must not create a second activity")
            .isEqualTo(1);
    }

    // ── criterion #3: active already exists → close incident without re-execution ──

    @Test
    void resolveIncidentWhenActiveExists_closesIncidentWithoutReExecution() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        ActivityEntity svc1 = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());

        // Create a second ERROR activity with an incident (simulating accumulated stale incidents)
        ActivityEntity staleError = new ActivityEntity();
        staleError.setId(UUID.randomUUID());
        staleError.setProcessInstanceId(pi);
        staleError.setToken(svc1.getToken());
        staleError.setBpmnElementId("svc");
        staleError.setStatus(ActivityStatus.ERROR);
        staleError.setCreatedAt(Instant.now());
        tx.execute(s -> activityRepository.save(staleError));
        IncidentEntity staleIncident = tx.execute(s -> createIncidentOnActivity(staleError));

        // Resolve the stale incident — svc1 is still CREATED (active), so guard kicks in
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(staleIncident.getId(), List.of()));

        // Incident closed, but svc1 still active — no re-execution happened
        assertThat(countActiveByElement(pi, "svc")).isEqualTo(1);
        IncidentEntity refreshed = tx.execute(s -> incidentRepository.findById(staleIncident.getId()).orElseThrow());
        assertThat(refreshed.getCompletedAt()).as("incident must be closed").isNotNull();
        ActivityStatus svc1Status = tx.execute(s -> activityRepository.findById(svc1.getId()).orElseThrow().getStatus());
        assertThat(svc1Status).as("active task must remain CREATED").isEqualTo(ActivityStatus.CREATED);
    }

    // ── criterion #5: stale incidents auto-closed on re-execution ──

    @Test
    void staleIncidentsAutoClosedOnReExecution() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        ActivityEntity svc1 = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());

        // Create a stale ERROR activity with an incident on the same (token, element)
        ActivityEntity staleError = new ActivityEntity();
        staleError.setId(UUID.randomUUID());
        staleError.setProcessInstanceId(pi);
        staleError.setToken(svc1.getToken());
        staleError.setBpmnElementId("svc");
        staleError.setStatus(ActivityStatus.ERROR);
        staleError.setCreatedAt(Instant.now());
        tx.execute(s -> activityRepository.save(staleError));
        IncidentEntity staleIncident = tx.execute(s -> createIncidentOnActivity(staleError));

        // Fail the current activity → incident
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1.getId(), "boom", 0));
        UUID inc1Id = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().stream()
            .filter(i -> i.getActivityId().equals(svc1.getId()))
            .map(com.zorrodev.bpm.contract.dto.Incident::getId)
            .findFirst().orElseThrow());

        // Resolve the main incident → re-execution should auto-close the stale incident
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(inc1Id, List.of()));

        // Both incidents closed
        assertThat(tx.execute(s -> incidentRepository.findById(staleIncident.getId()).orElseThrow()).getCompletedAt())
            .as("stale incident must be auto-closed").isNotNull();

        // Exactly 1 active activity
        assertThat(countActiveByElement(pi, "svc")).isEqualTo(1);
    }

    // ── criterion #6: invariant — not >1 active activity on (instance, token, element) ──

    @Test
    void invariant_noMoreThanOneActivePerTokenElement() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        UUID svc1 = tx.execute(s -> activeServiceTask(pi));
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1, "boom", 0));
        UUID incidentId = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().get(0).getId());

        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        assertThat(countActiveByElement(pi, "svc"))
            .as("invariant: at most 1 active activity per (token, element)")
            .isEqualTo(1);
    }

    // ── criterion #7: regression — late completion of superseded task does not advance token ──

    @Test
    void lateCompletionOfResolvedServiceTaskDoesNotAdvanceTokenTwice() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn")); // start -> svc -> end

        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        UUID svc1 = tx.execute(s -> activeServiceTask(pi));

        // worker fails it fatally -> incident
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc1, "boom", 0));
        UUID incidentId = tx.execute(s -> queryService.findIncidents(openIncidents(pi), null).getData().get(0).getId());

        // resolve -> svc1 is cancelled, a fresh svc2 is created and active
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));
        UUID svc2 = tx.execute(s -> activeServiceTask(pi));
        assertThat(svc2).isNotEqualTo(svc1);
        ActivityStatus svc1Status = tx.execute(s -> activityRepository.findById(svc1).orElseThrow().getStatus());
        assertThat(svc1Status).isEqualTo(ActivityStatus.CANCELLED);

        // a late / duplicate completion of the ORIGINAL task must be ignored (no token advance)
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc1, List.of()));
        Instant completedAfterStale = tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAfterStale)
            .as("completing the superseded task must not finish the process")
            .isNull();

        // completing the fresh task finishes the process exactly once
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svc2, List.of()));
        Instant completedAfterFresh = tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAfterFresh).isNotNull();

        long endEvents = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)
            .count());
        assertThat(endEvents).as("exactly one end event reached").isEqualTo(1);
    }
}
