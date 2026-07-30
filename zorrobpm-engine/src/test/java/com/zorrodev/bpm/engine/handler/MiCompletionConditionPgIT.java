package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
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
 * WO-ENG-8: MI completionCondition with completedInstances/totalInstances.
 * <p>
 * PROD-BUG: completionCondition "=completedInstances = totalInstances" evaluated against
 * process variables only — both are undefined → null=null → true → each MI completion
 * prematurely ends the multi-instance, causing downstream to fire multiple times.
 * <p>
 * Fix: inject completedInstances (=arrived), totalInstances (=expected), numberOfInstances,
 * numberOfCompleteInstances, numberOfActiveInstances, numberOfTerminatedInstances as
 * scope-local variables in {@code completionConditionMet()} before FEEL evaluation.
 * Also use evaluateExpression (not evaluateScript) since completionCondition is a FEEL
 * boolean expression, not a unary test.
 * <p>
 * Tagged {@code @Tag("pg")} via PostgresIT — excluded from default CI runs.
 */
public class MiCompletionConditionPgIT extends PostgresIT {

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

    private void ensureTx() {
        if (tx == null) tx = new TransactionTemplate(txManager);
    }

    /** Deploys and starts the test BPMN process. */
    private UUID startProcess(String bpmnFile) {
        ensureTx();
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

    /** Finds activities by process instance, BPMN element id, and status. */
    private List<ActivityEntity> findActivities(UUID piId, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == status)
            .toList();
    }

    /**
     * POF: parallel MI (2 instances) with completionCondition "=completedInstances = totalInstances".
     * RED (without fix): undefined variables → null=null → true → first completion ends MI →
     * process completes while second MI activity is still CREATED.
     * GREEN (with fix): completedInstances=1, totalInstances=2 → 1=2 → false → wait for
     * second completion; only when both are done does the process complete.
     */
    @Test
    void completionConditionWithStandardMiVars_waitsForAllInstances() {
        UUID piId = startProcess("test-eng-8-completioncondition.bpmn");

        // Verify: 2 parallel MI user tasks created
        List<ActivityEntity> tasks = findActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(tasks).as("Two parallel MI tasks should be active").hasSize(2);

        // ── Complete first MI task ─────────────────────────────────
        boolean firstCompletedPrematurely = tx.execute(s -> {
            runtimeService.completeUserTask(tasks.get(0).getId(), List.of());
            return queryService.getProcessInstance(piId).getCompletedAt() != null;
        });

        // RED (without fix): process completes after FIRST task (null=null → true → done)
        // GREEN (with fix): process stays alive, second task still active (1=2 → false → wait)
        assertThat(firstCompletedPrematurely)
            .as("Process must NOT complete after first MI task — completionCondition should wait for all")
            .isFalse();

        // ── Verify first task completed, second still active ────────
        List<ActivityEntity> completed = findActivities(piId, "miTask", ActivityStatus.COMPLETED);
        assertThat(completed).as("Exactly 1 MI task completed after first completion").hasSize(1);

        List<ActivityEntity> remaining = findActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(remaining).as("Second MI task still active").hasSize(1);

        // ── Complete second MI task ────────────────────────────────
        tx.execute(s -> {
            runtimeService.completeUserTask(remaining.get(0).getId(), List.of());
            return null;
        });

        // After both tasks done: process should be COMPLETED
        boolean completedAfterSecond = tx.execute(s ->
            queryService.getProcessInstance(piId).getCompletedAt() != null);
        assertThat(completedAfterSecond)
            .as("Process must complete after BOTH MI tasks are done")
            .isTrue();

        // Both MI tasks should be completed
        List<ActivityEntity> allCompleted = findActivities(piId, "miTask", ActivityStatus.COMPLETED);
        assertThat(allCompleted).as("Both MI tasks must be completed").hasSize(2);
    }
}
