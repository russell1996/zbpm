package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * WO-C8-35 (CR-09) direct unit coverage for {@code findConvergentInclusiveJoin} —
 * written after the G-H red-team found a non-terminating walk (a retry-loop diagram
 * hung the process start on 100 % CPU, a regression against the previous
 * {@code visited}-guarded BFS) and noted that no test contained a loop or a
 * multi-diamond region, which is exactly how the missing guard shipped.
 */
class ConvergentInclusiveJoinTest {

    private final BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

    private BpmnElementModel element(String id, BpmnElementType type) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(type);
        bpmn.addElement(e);
        return e;
    }

    /**
     * Adds a flow AND wires both endpoints' lists the way the parser does — the
     * model's {@code addFlow} only stores the row, so a hand-built graph would
     * otherwise have every element with zero incomings and the join test would pass
     * for the wrong reason (nothing qualifies, everything returns null).
     */
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

    @Test
    void convergesTheFirstJoinReachedByTwoOfTheSplitsBranches() {
        BpmnElementModel split = element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskA", BpmnElementType.USER_TASK);
        element("taskB", BpmnElementType.USER_TASK);
        BpmnElementModel join = element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("spA", "split", "taskA");
        flow("spB", "split", "taskB");
        flow("a1", "taskA", "join");
        flow("b1", "taskB", "join");
        flow("jEnd", "join", "end");

        assertThat(new ElementSupport(null, null, null, null, java.time.ZoneId.of("UTC"), false)
            .findConvergentInclusiveJoin(bpmn, split))
            .as("the join both branches reach is the partner")
            .isEqualTo("join");
    }

    @Test
    void skipsADecoyOnlyOneBranchReaches() {
        BpmnElementModel split = element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskA", BpmnElementType.USER_TASK);
        element("taskB", BpmnElementType.USER_TASK);
        BpmnElementModel decoy = element("decoy", BpmnElementType.INCLUSIVE_GATEWAY);
        BpmnElementModel join = element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("spA", "split", "taskA");
        flow("spB", "split", "taskB");
        flow("a1", "taskA", "decoy");
        flow("b1", "taskB", "join");
        flow("d1", "decoy", "join");

        assertThat(new ElementSupport(null, null, null, null, java.time.ZoneId.of("UTC"), false)
            .findConvergentInclusiveJoin(bpmn, split))
            .as("one branch cannot make a merge — the decoy is not the partner")
            .isEqualTo("join");
    }

    /**
     * The red-team blocker: split → taskA/taskB → join → taskC → back to taskA
     * (a plain retry loop-back). The walk must terminate and still find the join.
     * {@code assertTimeoutPreemptively} is the point of the test: without the settle
     * check this call never returns.
     */
    @Test
    void loopBackToTheSameRegion_terminatesAndStillFindsTheJoin() {
        BpmnElementModel split = element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskA", BpmnElementType.USER_TASK);
        element("taskB", BpmnElementType.USER_TASK);
        BpmnElementModel join = element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskC", BpmnElementType.USER_TASK);
        flow("spA", "split", "taskA");
        flow("spB", "split", "taskB");
        flow("a1", "taskA", "join");
        flow("b1", "taskB", "join");
        flow("j1", "join", "taskC");
        flow("retry", "taskC", "taskA"); // the loop-back that used to hang the walk

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertThat(new ElementSupport(null, null, null, null, java.time.ZoneId.of("UTC"), false)
                .findConvergentInclusiveJoin(bpmn, split))
                .isEqualTo("join"));
    }

    @Test
    void neverReturnsTheSplitItselfWhenALoopComesBackToIt() {
        BpmnElementModel split = element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskA", BpmnElementType.USER_TASK);
        element("taskB", BpmnElementType.USER_TASK);
        element("join", BpmnElementType.INCLUSIVE_GATEWAY);
        element("taskC", BpmnElementType.USER_TASK);
        flow("spA", "split", "taskA");
        flow("spB", "split", "taskB");
        flow("a1", "taskA", "join");
        flow("b1", "taskB", "join");
        flow("j1", "join", "taskC");
        // both branches loop back INTO the split — it becomes reachable with a 2-bit
        // mask, but it is not its own merge partner
        flow("backA", "taskC", "split");
        flow("backB", "join", "split");

        String found = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            new ElementSupport(null, null, null, null, java.time.ZoneId.of("UTC"), false)
                .findConvergentInclusiveJoin(bpmn, split));
        assertThat(found).isNotEqualTo("split");
    }

    /**
     * A chain of diamonds with no qualifying join: the walk must exhaust the graph and
     * return null. The first version re-expanded elements on every incoming edge, so this
     * shape grew ~14x per four diamonds and killed the JVM at 32 of them.
     */
    @Test
    void diamondChainWithoutAConvergingJoin_returnsNullPromptly() {
        BpmnElementModel split = element("split", BpmnElementType.INCLUSIVE_GATEWAY);
        flow("sp0", "split", "n0a");
        for (int i = 0; i < 25; i++) {
            element("n" + i + "a", BpmnElementType.USER_TASK);
            element("n" + i + "b", BpmnElementType.USER_TASK);
            element("p" + i, BpmnElementType.PARALLEL_GATEWAY); // parallel, never inclusive
        }
        for (int i = 0; i < 24; i++) {
            flow("f" + i + "a", "n" + i + "a", "p" + i);
            flow("f" + i + "b", "n" + i + "b", "p" + i);
            if (i > 0) {
                flow("f" + i + "in", "p" + (i - 1), "n" + i + "a");
                flow("f" + i + "in2", "p" + (i - 1), "n" + i + "b");
            }
            flow("f" + i + "out", "p" + i, "n" + (i + 1) + "a");
        }

        Instant started = Instant.now();
        String found = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
            new ElementSupport(null, null, null, null, java.time.ZoneId.of("UTC"), false)
                .findConvergentInclusiveJoin(bpmn, split));
        assertThat(found)
            .as("only parallel joins on the path — no inclusive partner, and it terminates")
            .isNull();
        assertThat(Duration.between(started, Instant.now()))
            .as("the walk must stay prompt on a 100+ element graph")
            .isLessThan(Duration.ofSeconds(5));
    }
}