package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-37 (C37-1): an ad-hoc subprocess owns a scope token (like
 * {@code SubProcessHandler}), so scope-confinement works for it — interrupting
 * boundaries kill only the scope subtree, terminate ends only the scope, and a
 * plain inner end is a join arrival rather than an instance exit.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class AdHocScopeTokenIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    private UUID start(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> rows(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .toList();
    }

    private boolean instanceDone(UUID pi) {
        return queryService.getProcessInstance(pi).getCompletedAt() != null;
    }

    private ProcessVariable booleanVar(String name, boolean value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(Boolean.toString(value));
        return v;
    }

    private UUID createdTask(UUID pi, String elementId) {
        return rows(pi, elementId).stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
    }

    @Test
    void interruptingBoundary_killsOnlyScopeSubtree_siblingSurvives() throws Exception {
        UUID pi = start("test-c837-adhoc-boundary-scope.bpmn");
        assertThat(rows(pi, "taskA")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "outerTask")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        UUID scopeRow = rows(pi, "adhoc").get(0).getId();
        activityService.fireBoundaryTimer(scopeRow, "adhocTimeout");

        assertThat(rows(pi, "taskA")).anyMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(rows(pi, "outerTask")).allMatch(a -> a.getStatus() != ActivityStatus.CANCELLED);
    }

    @Test
    void terminateInsideAdhoc_endsOnlyScope_parentFlowContinues() throws Exception {
        UUID pi = start("test-c837-adhoc-terminate-scope.bpmn");
        UUID taskA = rows(pi, "taskA").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(taskA, List.of());

        assertThat(instanceDone(pi)).isFalse();
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(rows(pi, "afterAdhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "outerTask")).allMatch(a -> a.getStatus() != ActivityStatus.CANCELLED);

        UUID afterAdhoc = rows(pi, "afterAdhoc").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(afterAdhoc, List.of());
        UUID outer = rows(pi, "outerTask").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(outer, List.of());
        assertThat(instanceDone(pi)).isTrue();
    }

    @Test
    void plainInnerEnd_isJoinArrival_scopeFinishesOnceAndContinues() throws Exception {        UUID pi = start("test-c837-adhoc-inner-end.bpmn");
        UUID taskA = rows(pi, "taskA").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(taskA, List.of());

        assertThat(instanceDone(pi)).isFalse();
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(rows(pi, "afterAdhoc")).hasSize(1);
        assertThat(rows(pi, "afterAdhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        UUID afterAdhoc = rows(pi, "afterAdhoc").get(0).getId();
        runtimeService.completeUserTask(afterAdhoc, List.of());
        assertThat(instanceDone(pi)).isTrue();
    }

    /**
     * WO-C8-37 (раунд 2, БЛОКИРУЮЩАЯ №1): корень с живой цепочкой (taskA→taskA2)
     * плюс корень-тупик (taskB). Завершить taskA (течёт в taskA2), затем taskB
     * (2/2 arrivals) — скоуп обязан ЖДАТЬ taskA2, а не финишировать досрочно
     * с отменой живой taskA2.
     */
    @Test
    void liveChainPlusDeadEnd_deadEndFinishDoesNotCloseScope() throws Exception {
        UUID pi = start("test-c837-adhoc-chain-plus-deadend.bpmn");
        assertThat(rows(pi, "taskA")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskB")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        runtimeService.completeUserTask(createdTask(pi, "taskA"), List.of());
        assertThat(rows(pi, "taskA2")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        runtimeService.completeUserTask(createdTask(pi, "taskB"), List.of());

        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskA2")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskA2")).noneMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(rows(pi, "afterAdhoc")).isEmpty();
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(createdTask(pi, "taskA2"), List.of());
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(rows(pi, "afterAdhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        runtimeService.completeUserTask(createdTask(pi, "afterAdhoc"), List.of());
        assertThat(instanceDone(pi)).isTrue();
    }

    /**
     * WO-C8-37 (раунд 2, БЛОКИРУЮЩАЯ №1, вложенный вариант): внутренний ad-hoc
     * финиширует досрочно по completionCondition с cancelRemainingInstances=false
     * (taskI2 остаётся жить), затем финиширует taskB (2/2 arrivals внешнего join).
     * Внешний скоуп обязан ждать taskI2 — внутренности лежат на внутреннем
     * scope-токене, невидимом для проверки «тишины» по входящему токену.
     */
    @Test
    void nestedAdhocSparedInnerTask_outerScopeWaitsForIt() throws Exception {
        UUID pi = start("test-c837-adhoc-nested-chain.bpmn");
        assertThat(rows(pi, "taskI1")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskI2")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskB")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        runtimeService.completeUserTask(createdTask(pi, "taskI1"), List.of(booleanVar("stopEarly", true)));
        assertThat(rows(pi, "inner")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(rows(pi, "taskI2")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "afterAdhoc")).isEmpty();

        runtimeService.completeUserTask(createdTask(pi, "taskB"), List.of());

        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskI2")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);
        assertThat(rows(pi, "taskI2")).noneMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(rows(pi, "afterAdhoc")).isEmpty();
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(createdTask(pi, "taskI2"), List.of());
        assertThat(rows(pi, "adhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(rows(pi, "afterAdhoc")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        runtimeService.completeUserTask(createdTask(pi, "afterAdhoc"), List.of());
        assertThat(instanceDone(pi)).isTrue();
    }
}
