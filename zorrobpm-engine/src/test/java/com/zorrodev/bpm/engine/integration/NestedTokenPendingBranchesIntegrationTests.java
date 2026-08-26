package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-13: nested token with exhausted pendingBranches must not complete the
 * process instance if its parent token still has pending branches.
 *
 * The trigger is a degenerate parallelGateway (1-in, 1-out) inside an implicit fork.
 * ParallelGatewayHandler creates a nested token with pendingBranches=1 for this
 * gateway. When the nested token reaches an end event and decrements to 0, it must
 * NOT call completeProcessInstance — the parent token (from the implicit fork) still
 * has pending branches.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class NestedTokenPendingBranchesIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ActivityEntity findActiveActivity(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow(() -> new AssertionError("active activity not found: " + bpmnElementId));
    }

    /**
     * Criterion 1: nested token from degenerate parallelGateway reaches end event,
     * but parent token (implicit fork) still has pending branches → instance stays RUNNING.
     */
    @Transactional
    @Test
    void criterion1_nestedTokenExhausted_doesNotCompleteIfParentHasPending() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-nested-token-pending-branches.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // forkTask is the implicit fork (2 outgoing, not a gateway)
        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // After forking:
        // Branch 1: forkTask → degenerateGateway → serviceA → endA
        // Branch 2: forkTask → userB (still waiting)
        //
        // The degenerate gateway creates a nested token with pendingBranches=1.
        // Complete the short branch through the gateway.
        ActivityEntity serviceA = findActiveActivity(piId, "serviceA");
        runtimeService.completeServiceTask(serviceA.getId(), List.of());

        // CRITICAL: instance must still be RUNNING — userB branch is still active.
        // Before the fix, the nested token from degenerateGateway would reach endA,
        // decrement its pendingBranches to 0, and call completeProcessInstance,
        // prematurely killing the entire instance while userB is still waiting.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must stay RUNNING after nested token branch finishes — parent still has pending branches")
            .isNull();

        // userB should still be active
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("userB"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .count()).isOne();
    }

    /**
     * Criterion 2: after both branches (including the nested token branch) finish,
     * the instance completes normally (regression — not broken by the fix).
     */
    @Transactional
    @Test
    void criterion2_outerTokenExhausted_completesNormally() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-nested-token-pending-branches.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // Complete the short branch (through degenerate gateway)
        ActivityEntity serviceA = findActiveActivity(piId, "serviceA");
        runtimeService.completeServiceTask(serviceA.getId(), List.of());

        // Complete the long branch (userB)
        ActivityEntity userB = findActiveActivity(piId, "userB");
        runtimeService.completeUserTask(userB.getId(), List.of());

        // Now both branches are done → instance should be COMPLETED
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must be COMPLETED after both branches finished")
            .isNotNull();
    }

    /**
     * Criterion 3: degenerate parallelGateway (1-in, 1-out) inside implicit fork
     * does not cause premature completion. This is the core regression test.
     */
    @Transactional
    @Test
    void criterion3_degenerateParallelGateway_doesNotPrematurelyComplete() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-nested-token-pending-branches.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // Complete the degenerate gateway branch (serviceA → endA)
        ActivityEntity serviceA = findActiveActivity(piId, "serviceA");
        runtimeService.completeServiceTask(serviceA.getId(), List.of());

        // The degenerate gateway branch is done, but userB is still active.
        // Instance must NOT be completed.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("degenerate parallelGateway branch completing must not kill the instance")
            .isNull();
    }

    /**
     * Criterion 4: real parallel split (2 branches via implicit fork) still works.
     * Both branches have tasks, both complete, instance completes.
     * Uses the existing test-implicit-fork-two-way.bpmn (no gateway in the path).
     */
    @Transactional
    @Test
    void criterion4_realParallelSplit_completesAfterAllBranches() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-implicit-fork-two-way.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // Complete short branch
        ActivityEntity serviceA = findActiveActivity(piId, "serviceA");
        runtimeService.completeServiceTask(serviceA.getId(), List.of());

        // Instance still running
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must stay RUNNING while userB is active")
            .isNull();

        // Complete long branch
        ActivityEntity userB = findActiveActivity(piId, "userB");
        runtimeService.completeUserTask(userB.getId(), List.of());

        // Instance completed
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must be COMPLETED after both branches finished")
            .isNotNull();
    }

    /**
     * Criterion 5 (CTO HOLD fix): recursive bubble-up across the FULL parent-token chain.
     *
     * Implicit fork (forkTask, 2 outgoing):
     *   Branch A: forkTask → gwA (1 degenerate gateway) → endA
     *   Branch B: forkTask → gwB → gwB2 (2 degenerate gateways in a row) → endB
     *
     * Token nesting: T1 (fork, pending=2) → gwA creates T2 (pending=1) → endA;
     * T1 → gwB creates T3 (pending=1) → gwB2 creates T4 (pending=1) → endB.
     *
     * With the SINGLE-LEVEL bubble-up (pre-fix):
     *   - endA finishes T2 (1→0): parent T1 pending=2>0 → decrement T1 → 1, return.
     *   - endB finishes T4 (1→0): parent T3 pending=1>0 → decrement T3 → 0, return.
     *   T3 was itself exhausted *through* that bubble-up, but the chain never reaches the
     *   grandparent T1. T1.pending stays at 1 → instance HANGS forever (silently, no incident).
     *
     * With the RECURSIVE fix:
     *   - endB finishes T4 (1→0): parent T3 pending=1>0 → decrement T3 → 0, move up to T3;
     *     T3 pending=0, parent T1 pending=1>0 → decrement T1 → 0, move up to T1;
     *     T1 pending=0, no pending parent → complete instance.
     */
    @Transactional
    @Test
    void criterion5_threeLevelNesting_completesAfterAllBranches() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-nested-token-3level.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // forkTask is the implicit fork (2 outgoing, not a gateway)
        ActivityEntity fork = findActiveActivity(piId, "forkTask");
        runtimeService.completeServiceTask(fork.getId(), List.of());

        // Both branches auto-propagate through degenerate gateways to endA / endB.
        // After all branches finish, the recursive bubble-up must have reached the root fork
        // token (T1), which is now exhausted → instance COMPLETED.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("instance must COMPLETE after BOTH branches finish (endA + endB) — recursive bubble-up must reach the root fork token")
            .isNotNull();
    }
}
