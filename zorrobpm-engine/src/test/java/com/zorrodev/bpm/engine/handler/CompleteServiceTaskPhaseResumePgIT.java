package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DEBT-6 Срез 0: characterization test for completeServiceTask / failServiceTask.
 * Deploys test3.bpmn (start → serviceTask1 → end), starts a process instance,
 * completes/fails the service task, and verifies the resulting state.
 * <p>
 * Прод-код НЕ трогается — только новый интеграционный тест.
 * <p>
 * Covers: guard-status check (CREATED/IN_PROGRESS → complete; COMPLETED → ignore),
 * failServiceTask retries-exhausted and retries-left paths.
 * <p>
 * NOT covered in this slice (requires specialized BPMN models with listeners/user tasks):
 * phase-resume, creating-listeners, user-task-row, outbox-enqueue — documented as
 * НЕ СДЕЛАНО in the report.
 */
@Tag("pg")
public class CompleteServiceTaskPhaseResumePgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired QueryService queryService;
    @Autowired ActivityRepository activityRepository;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    /** Deploys test3.bpmn (start → serviceTask1 → end) and starts a process instance. */
    private UUID startServiceTaskProcess() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test3.bpmn"));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean isDone(UUID pi) {
        return tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt() != null);
    }

    private UUID firstCreatedId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == ActivityStatus.CREATED)
                .map(a -> a.getId())
                .findFirst().orElse(null));
    }

    private long countByElement(UUID pi, String elementId, ActivityStatus status) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == status)
                .count());
    }

    /**
     * Counts endEvent activities in the instance. Characterizes the no-double-advance
     * invariant: one service-task completion must produce exactly one endEvent row —
     * a redelivered completion that slips past the guard would advance the token
     * twice and create a second one (P-67: asserting only the service-task row
     * cannot distinguish «guard ignored» from «guard bypassed», the second
     * completion just re-updates the same row).
     */
    private long countEndEvents(UUID pi) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals("endEvent"))
                .count());
    }

    // ----------------------------------------------------------------
    // tests
    // ----------------------------------------------------------------

    /**
     * Criterion 1 (guard-status): completeServiceTask on a CREATED service task
     * should complete it normally (happy path). The guard check passes because
     * status == CREATED.
     */
    @Test
    void completeServiceTask_createdStatus_completesNormally() {
        UUID pi = startServiceTaskProcess();

        // serviceTask1 should be CREATED (waiting for worker)
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.CREATED))
                .as("serviceTask1 should be CREATED before completion")
                .isEqualTo(1);

        // Find the service task and complete it
        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        assertThat(serviceTaskId).as("serviceTask1 must exist").isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(serviceTaskId, List.of()));

        // serviceTask1 should now be COMPLETED
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.COMPLETED))
                .as("serviceTask1 should be COMPLETED after completion")
                .isEqualTo(1);

        // Exactly one endEvent row — the token advanced once, not twice
        assertThat(countEndEvents(pi))
                .as("exactly one endEvent row after single completion")
                .isEqualTo(1);

        // Process instance should be completed (start → serviceTask → end)
        assertThat(isDone(pi))
                .as("Process instance should be completed after service task completion")
                .isTrue();
    }

    /**
     * Criterion 2 (guard-status): completeServiceTask on a COMPLETED activity
     * should be ignored (at-least-once redelivery tolerance).
     */
    @Test
    void completeServiceTask_alreadyCompleted_ignored() {
        UUID pi = startServiceTaskProcess();

        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        assertThat(serviceTaskId).isNotNull();

        // Complete once — should succeed
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(serviceTaskId, List.of()));
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.COMPLETED))
                .as("serviceTask1 should be COMPLETED after first completion")
                .isEqualTo(1);
        assertThat(countEndEvents(pi))
                .as("one endEvent row after first completion")
                .isEqualTo(1);

        // Complete again — should be ignored (no crash, no double-advance)
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(serviceTaskId, List.of()));

        // Still exactly 1 COMPLETED, process still done
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.COMPLETED))
                .as("serviceTask1 should still be COMPLETED (no double-advance)")
                .isEqualTo(1);
        // The guard must have swallowed the redelivery: no second endEvent row
        assertThat(countEndEvents(pi))
                .as("guard must prevent double-advance: still exactly 1 endEvent row")
                .isEqualTo(1);
        assertThat(isDone(pi))
                .as("Process instance should still be completed")
                .isTrue();
    }

    /**
     * Criterion 3 (failServiceTask): failServiceTask with retries=0 should
     * raise an incident and park the activity.
     */
    @Test
    void failServiceTask_retriesExhausted_raisesIncident() {
        UUID pi = startServiceTaskProcess();

        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        assertThat(serviceTaskId).isNotNull();

        // Fail with retries=0 → incident
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(serviceTaskId, "Worker crashed", 0));

        // Activity should be ERROR (parked on incident)
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.ERROR))
                .as("serviceTask1 should be ERROR after retries exhausted")
                .isEqualTo(1);

        // Process instance should NOT be completed (stuck on incident)
        assertThat(isDone(pi))
                .as("Process instance should NOT be completed while incident is open")
                .isFalse();
    }

    /**
     * Criterion 4 (failServiceTask retries-decrement): failServiceTask with
     * retries=3 should re-dispatch the job (activity stays CREATED).
     */
    @Test
    void failServiceTask_retriesLeft_redispatches() {
        UUID pi = startServiceTaskProcess();

        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        assertThat(serviceTaskId).isNotNull();

        // Fail with retries=3 → re-dispatch
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(serviceTaskId, "Temporary failure", 3));

        // Activity should still be CREATED (re-dispatched, not parked)
        assertThat(countByElement(pi, "serviceTask1", ActivityStatus.CREATED))
                .as("serviceTask1 should be CREATED after re-dispatch")
                .isEqualTo(1);

        // Process instance should NOT be completed
        assertThat(isDone(pi))
                .as("Process instance should NOT be completed while retries remain")
                .isFalse();
    }
}
