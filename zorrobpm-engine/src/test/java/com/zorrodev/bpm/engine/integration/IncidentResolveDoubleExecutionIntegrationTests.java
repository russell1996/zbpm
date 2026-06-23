package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
 * Regression for the incident-resolve double-execution bug: resolving an incident re-executes the
 * element as a fresh activity; a late/duplicate completion of the ORIGINAL (now superseded) service
 * task must NOT advance the token a second time.
 *
 * Each step runs in its OWN transaction via {@link TransactionTemplate} — mirroring the real
 * per-HTTP-request transaction so committed state is visible across steps (a single {@code @Transactional}
 * test would hide the bulk status update behind the L1 cache and could not reproduce the bug).
 * Assertions are scoped to this test's own process instance, so the shared H2 schema is fine.
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
        UUID incidentId = tx.execute(s -> queryService.findIncidents(openIncidents(pi)).getData().get(0).getId());

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
