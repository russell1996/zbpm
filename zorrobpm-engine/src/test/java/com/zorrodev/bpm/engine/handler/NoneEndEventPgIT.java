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
 * WO-ENG-1: Parallel none-end-event must not prematurely complete the process instance
 * while other branches are still active.
 * <p>
 * The fix tracks pending outgoing branches on the token created by the parallel gateway
 * via {@link FlowNavigator#setTokenPendingBranches}. {@code finishBranch} decrements
 * this counter and only completes the process instance when all branches have reached an
 * end event.
 * <p>
 * POF (red): Remove the pending-branch check from {@link FlowNavigator#finishBranch}
 * and the {@code setTokenPendingBranches} call from {@link ParallelGatewayHandler} →
 * the test below fails because the process instance is completed when endA is reached,
 * even though userTask1 is still active.
 * <p>
 * POF (green): With both changes in place, the counter prevents premature completion.
 */
public class NoneEndEventPgIT extends PostgresIT {

    @Autowired
    ProcessDefinitionService processDefinitionService;
    @Autowired
    RuntimeService runtimeService;
    @Autowired
    QueryService queryService;
    @Autowired
    ActivityRepository activityRepository;
    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    /** Deploys and starts test-parallel-end.bpmn. */
    private UUID startParallelEnd() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test-parallel-end.bpmn"));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Deploys and starts test1.bpmn (linear start → end). */
    private UUID startLinear() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test1.bpmn"));
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

    private long countActive(UUID pi) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getStatus() == ActivityStatus.CREATED)
                .count());
    }

    private long countByElement(UUID pi, String elementId, ActivityStatus status) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == status)
                .count());
    }

    private UUID firstCreatedId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == ActivityStatus.CREATED)
                .map(a -> a.getId())
                .findFirst().orElse(null));
    }

    // ----------------------------------------------------------------
    // tests
    // ----------------------------------------------------------------

    /**
     * Criterion 1: Parallel split with one path to a none-end-event — the instance
     * must NOT be completed while the other branch (user task) is still active.
     */
    @Test
    void parallelSplit_endEvent_doesNotCompleteInstance() {
        UUID pi = startParallelEnd();

        // The instance must still be RUNNING — the user task on the other branch is active
        assertThat(isDone(pi))
                .as("Process instance should NOT be completed while userTask1 is active")
                .isFalse();

        // userTask1 must be CREATED (active)
        assertThat(countByElement(pi, "userTask1", ActivityStatus.CREATED))
                .as("userTask1 should be CREATED")
                .isEqualTo(1);

        // endA must be COMPLETED (the none-end-event on the first branch)
        assertThat(countByElement(pi, "endA", ActivityStatus.COMPLETED))
                .as("endA should be COMPLETED")
                .isEqualTo(1);

        // Exactly 1 active activity remains (userTask1)
        assertThat(countActive(pi))
                .as("Exactly one active activity should remain")
                .isEqualTo(1);
    }

    /**
     * Criterion 2: Linear process (start → none-end) must still complete normally.
     * This ensures the fix does not break the simple happy path.
     */
    @Test
    void linearProcess_completesNormally() {
        UUID pi = startLinear();

        // The instance must be COMPLETED (was a simple linear flow)
        assertThat(isDone(pi))
                .as("Linear process (start→end) should complete normally")
                .isTrue();
    }

    /**
     * Criterion 3: After completing the user task, the second end event is reached
     * and there are no active activities left → the instance completes.
     */
    @Test
    void allBranchesComplete_afterLastEndEvent() {
        UUID pi = startParallelEnd();

        // Complete the user task
        UUID userTaskId = firstCreatedId(pi, "userTask1");
        assertThat(userTaskId).as("userTask1 must exist").isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(userTaskId, List.of()));

        // Now both branches have hit their end events — the instance must be COMPLETED
        assertThat(isDone(pi))
                .as("Process instance should complete after both branches finish")
                .isTrue();
    }
}
