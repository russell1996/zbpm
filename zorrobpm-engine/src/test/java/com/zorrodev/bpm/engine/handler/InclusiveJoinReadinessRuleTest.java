package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-C8-35 (CR-09) раунд 3: direct unit coverage of the ONE inclusive-join readiness rule.
 *
 * <p>Replaces {@code ConvergentInclusiveJoinTest} (deleted with the static counter it covered:
 * {@code findConvergentInclusiveJoin} existed only to compute {@code expected}, and the red-team
 * showed that counter was the defect — a blind INSERT into a UK-constrained table and a target
 * that could be a 2-of-3 convergence). The red-team also noted the old envelope had NO direct
 * test for {@code canReach} / {@code hasOtherLiveExecutionReaching} and that mutation
 * {@code canReach -> true} left all five of those tests green; these tests are written so that
 * mutation must fail several of them (see the report's mutation table).
 *
 * <p>The two "can still deliver" sets (round 5: the persisted ARMED-trigger set is gone): live
 * activities — each together with the boundary events attached to its element, which is where
 * BLOCKER-3 lived — and parked joins (a branch waiting at another gateway, MAJOR-1's
 * partial-merge shape).
 */
class InclusiveJoinReadinessRuleTest {

    private final DBService dbService = Mockito.mock(DBService.class);
    private final ScriptService scriptService = Mockito.mock(ScriptService.class);
    private final FeelBudget feelBudget = Mockito.mock(FeelBudget.class);
    private final tools.jackson.databind.ObjectMapper objectMapper =
        new tools.jackson.databind.ObjectMapper();
    private ElementSupport elementSupport;
    private BpmnProcessDefinitionModel bpmn;

    @BeforeEach
    void setUp() {
        elementSupport = new ElementSupport(dbService, scriptService, feelBudget, objectMapper,
            ZoneId.of("Asia/Almaty"), false);
        bpmn = new BpmnProcessDefinitionModel();
        lenient().when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of());
        lenient().when(dbService.getActiveActivities(any())).thenReturn(List.of());
    }

    private BpmnElementModel element(String id, BpmnElementType type) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(type);
        bpmn.addElement(e);
        return e;
    }

    /** Wires the flow AND both endpoint lists the way the parser does. */
    private void flow(String id, String from, String to) {
        BpmnFlowModel f = new BpmnFlowModel();
        f.setFlowId(id);
        f.setSourceRef(from);
        f.setTargetRef(to);
        bpmn.addFlow(f);
        BpmnElementModel source = bpmn.getElement(from);
        BpmnElementModel target = bpmn.getElement(to);
        if (source != null) {
            source.getOutgoing().add(id);
        }
        if (target != null) {
            target.getIncoming().add(id);
        }
    }

    /** Граничное событие, привязанное к хосту — ровно так, как их кладёт парсер (attachedToRef). */
    private BpmnElementModel boundary(String id, String hostId) {
        BpmnElementModel b = element(id, BpmnElementType.CONDITIONAL_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel be = new BoundaryEventExtensionModel();
        be.setAttachedToRef(hostId);
        be.setInterrupting(false);
        ext.setBoundaryEventExtension(be);
        b.setExtensions(ext);
        return b;
    }

    private Activity liveOn(String elementId) {
        Activity a = new Activity();
        a.setId(UUID.randomUUID());
        a.setBpmnElementId(elementId);
        return a;
    }

    @Test
    void canReach_followsOutgoingFlowsForward() {
        element("t1", BpmnElementType.USER_TASK);
        element("xor", BpmnElementType.EXCLUSIVE_GATEWAY);
        element("t2", BpmnElementType.USER_TASK);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("f1", "t1", "xor");
        flow("f2", "xor", "t2");
        flow("f3", "t2", "join");

        assertThat(elementSupport.canReach(bpmn, "t1", "join")).isTrue();
        assertThat(elementSupport.canReach(bpmn, "join", "t1")).isFalse();
        assertThat(elementSupport.canReach(bpmn, "t1", "t1")).isTrue();
    }

    @Test
    void canReach_terminatesOnCyclicGraph() {
        // The G-H red-team pinned a NON-terminating walk here (the removed
        // findConvergentInclusiveJoin hung the process start at 100% CPU on a retry-loop).
        // Reachability is reached on every deactivation now, so the guard must stay pinned.
        element("xor", BpmnElementType.EXCLUSIVE_GATEWAY);
        element("t1", BpmnElementType.USER_TASK);
        element("t2", BpmnElementType.USER_TASK);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("f1", "xor", "t1");
        flow("f2", "t1", "t2");
        flow("f3", "t2", "xor");
        flow("f4", "t2", "join");

        assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThat(elementSupport.canReach(bpmn, "xor", "join")).isTrue());
    }

    @Test
    void canReach_countsAFalseConditionPathAsReachable_onPurpose() {
        // Conservative by design: a path through a conditional gateway counts even when its
        // condition may be false. Mutation of THIS behaviour into "conditions decide reachability"
        // would silently reintroduce BLOCKER-1's early firing.
        element("xor", BpmnElementType.EXCLUSIVE_GATEWAY);
        element("taskOk", BpmnElementType.USER_TASK);
        element("taskNg", BpmnElementType.USER_TASK);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("fOk", "xor", "taskOk");
        flow("fNg", "xor", "taskNg");
        flow("g1", "taskNg", "join");

        assertThat(elementSupport.canReach(bpmn, "xor", "join")).isTrue();
    }

    @Test
    void hasOtherLiveExecutionReaching_ignoresTheJoinsOwnActivity() {
        element("taskA", BpmnElementType.USER_TASK);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("f1", "taskA", "join");
        BpmnElementModel join = bpmn.getElement("join");

        when(dbService.getActiveActivities(any())).thenReturn(List.of(liveOn("join")));
        assertThat(elementSupport.hasOtherLiveExecutionReaching(UUID.randomUUID(), bpmn, join)).isFalse();

        when(dbService.getActiveActivities(any())).thenReturn(List.of(liveOn("taskA")));
        assertThat(elementSupport.hasOtherLiveExecutionReaching(UUID.randomUUID(), bpmn, join)).isTrue();
    }

    @Test
    void canReach_goesThroughABoundaryAttachedToTheHostByAttachedToRef() {
        // BLOCKER-3 red-team (живой прогон): условная граница привязана к хосту через
        // attachedToRef, а не потоком, и строки armed у неё НЕТ ВООБЩЕ. Если canReach идёт
        // только по sequence-потокам хоста, хост с живой границей выглядит «не может доставить»
        // — join срабатывает на первом приходе и ВТОРЫМ на приходе от границы (taskAfter дважды).
        element("taskA", BpmnElementType.USER_TASK);
        element("taskHold", BpmnElementType.USER_TASK);
        element("endHold", BpmnElementType.END_EVENT);
        boundary("condB", "taskHold");   // непрерывающая условная граница на taskHold
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("gC", "taskA", "join");
        flow("fHoldEnd", "taskHold", "endHold");
        flow("fBnd", "condB", "join");

        assertThat(elementSupport.canReach(bpmn, "taskHold", "join"))
            .as("живой хост + его граница = возможный доставщик ветви в join")
            .isTrue();
        assertThat(elementSupport.canReach(bpmn, "taskA", "join")).isTrue();
        assertThat(elementSupport.canReach(bpmn, "endHold", "join"))
            .as("end без исходящих потоков и без границ ничего доставить не может")
            .isFalse();
    }

    @Test
    void canReach_boundaryOfADeadHostIsGone_becauseTheHostIsNoLongerLive() {
        // Ровно то свойство, которое раньше держала armed-таблица: пока хост жив — граница может
        // выстрелить; хост умер — он исчез из множества живых activity, и его граница снята.
        // Здесь это чисто структурное свойство: НИ ОДНОГО запроса к armed-таблицам не делается.
        element("taskHold", BpmnElementType.USER_TASK);
        boundary("tmrCheck", "taskHold");
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("fBnd", "tmrCheck", "join");
        BpmnElementModel join = bpmn.getElement("join");
        UUID pi = UUID.randomUUID();

        when(dbService.getActiveActivities(any())).thenReturn(List.of(liveOn("taskHold")));
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join))
            .as("taskHold жив, его граница ведёт в join — ждать")
            .isFalse();

        // та же диаграмма, хост мёртв (его нет среди живых activity) — доставлять некому
        when(dbService.getActiveActivities(any())).thenReturn(List.of());
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join))
            .as("мёртвый хост = снятая граница, никто доставить не может — join готов")
            .isTrue();
    }

    @Test
    void hasParkedJoinReaching_seesABranchWaitingAtAnotherGateway() {
        // MAJOR-1 shape: j1 holds A/B and parks, j2 waits for j1's outgoing. Firing j2 first would
        // run its downstream while j1's branch is still in flight.
        element("j1", BpmnElementType.INCLUSIVE_GATEWAY);
        element("j2", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("jOut", "j1", "j2");
        flow("gC", "split", "j2");
        element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        BpmnElementModel j2 = bpmn.getElement("j2");

        when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of("j1"));
        assertThat(elementSupport.hasParkedJoinReaching(UUID.randomUUID(), bpmn, j2)).isTrue();

        when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of("j2"));
        assertThat(elementSupport.hasParkedJoinReaching(UUID.randomUUID(), bpmn, j2)).isFalse();
    }

    @Test
    void isInclusiveJoinReady_trueOnlyWhenNoSetCanDeliver() {
        element("taskLive", BpmnElementType.USER_TASK);
        element("taskHold", BpmnElementType.USER_TASK);
        boundary("condB", "taskHold");   // граница привязана к taskHold и ведёт прямо в join
        element("j1", BpmnElementType.INCLUSIVE_GATEWAY);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("f1", "taskLive", "join");
        flow("fBnd", "condB", "join");
        flow("jOut", "j1", "join");
        BpmnElementModel join = bpmn.getElement("join");
        UUID pi = UUID.randomUUID();

        // quiet instance → ready
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join)).isTrue();

        // a live activity reaches it → must wait
        when(dbService.getActiveActivities(any())).thenReturn(List.of(liveOn("taskLive")));
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join)).isFalse();

        // only a LIVE HOST WHOSE BOUNDARY reaches it → must still wait (BLOCKER-3)
        when(dbService.getActiveActivities(any())).thenReturn(List.of(liveOn("taskHold")));
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join)).isFalse();

        // only a parked join reaches it → must still wait
        when(dbService.getActiveActivities(any())).thenReturn(List.of());
        when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of("j1"));
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join)).isFalse();

        // everything silent again → ready (the resume path's GREEN)
        when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of());
        assertThat(elementSupport.isInclusiveJoinReady(pi, bpmn, join)).isTrue();
    }
}