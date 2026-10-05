package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WO-C8-34 (CR-06), CTO HOLD 2026-10-05 п.2 — the MILLISECOND boundary of the
 * throw-time snapshot. A compensation target belongs to a thrower iff it completed
 * at or before the thrower's own row creation time. Both sides of that tie are
 * pinned here, because both are wrong-by-one lines away:
 *
 * <ul>
 *   <li>{@code completedAt == thrower.createdAt} — the SAME millisecond: the target
 *       STAYS owned (fail-closed), so the thrower keeps waiting for its handler.
 *       Cutting it would silently stop waiting for a compensation it launched.</li>
 *   <li>{@code completedAt == thrower.createdAt + 1ms} — strictly later: the target
 *       is CUT. It was not compensated by this throw (its handler was never
 *       launched), and keeping it would strand the thrower forever waiting for a
 *       handler that does not exist (red-team B1).</li>
 * </ul>
 *
 * <p>Unit level on purpose: the cut is timestamp arithmetic over rows the engine
 * writes with database-generated timestamps, and a live integration test cannot put
 * a target on an exact millisecond tie deterministically (P-10). The live
 * counterpart of the B1 drift is
 * {@code CompensationWaitIntegrationTests.waitForCompletionTrue_targetCompletingAfterTheThrow_doesNotStrandTheThrower}.
 */
@ExtendWith(MockitoExtension.class)
class CompensationSnapshotBoundaryTest {

    @Mock private DBService dbService;
    @Mock private ScriptService scriptService;
    @Mock private FeelBudget feelBudget;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ElementSupport elementSupport;

    private static final UUID PI = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();
    private static final UUID THROWER = UUID.randomUUID();
    private static final Instant THROWN_AT = Instant.parse("2026-10-05T10:00:00.000Z");

    @BeforeEach
    void setUp() {
        elementSupport = new ElementSupport(dbService, scriptService, feelBudget, objectMapper,
            java.time.ZoneId.of("Asia/Almaty"), false);

        Activity thrower = new Activity();
        thrower.setId(THROWER);
        thrower.setProcessInstanceId(PI);
        thrower.setToken(TOKEN);
        thrower.setBpmnElementId("compThrow");
        thrower.setType(BpmnElementType.COMPENSATION_THROW_EVENT);
        thrower.setStatus(ActivityStatus.CREATED);
        thrower.setCreatedAt(THROWN_AT);
        when(dbService.getActivity(THROWER)).thenReturn(thrower);
    }

    /** Boundary with a compensation handler attached to {@code hostId}. */
    private static BpmnProcessDefinitionModel model(String hostId, String handlerId) {
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId(hostId + "Boundary");
        boundary.setType(BpmnElementType.COMPENSATION_BOUNDARY_EVENT);
        BoundaryEventExtensionModel bext = new BoundaryEventExtensionModel();
        bext.setAttachedToRef(hostId);
        bext.setCompensationHandlerId(handlerId);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        ext.setBoundaryEventExtension(bext);
        boundary.setExtensions(ext);

        BpmnElementModel thrower = new BpmnElementModel();
        thrower.setId("compThrow");
        thrower.setType(BpmnElementType.COMPENSATION_THROW_EVENT);

        // the compensation handler element itself lives in the model (that is what
        // compensationHandlerId resolves against) — a fixture without it would make
        // every target look handler-less, i.e. "nothing to wait for"
        BpmnElementModel handler = new BpmnElementModel();
        handler.setId(handlerId);
        handler.setType(BpmnElementType.SERVICE_TASK);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(boundary);
        bpmn.addElement(thrower);
        bpmn.addElement(handler);
        return bpmn;
    }

    private static Activity completed(UUID token, String elementId, Instant completedAt) {
        Activity a = new Activity();
        a.setId(UUID.randomUUID());
        a.setProcessInstanceId(PI);
        a.setToken(token);
        a.setBpmnElementId(elementId);
        a.setStatus(ActivityStatus.COMPLETED);
        a.setCreatedAt(completedAt.minusSeconds(5));
        a.setCompletedAt(completedAt);
        return a;
    }

    /** Handler row left open (CREATED) → the thrower must keep waiting. */
    private void handlerOpen(String handlerId) {
        Activity open = new Activity();
        open.setId(UUID.randomUUID());
        open.setProcessInstanceId(PI);
        open.setToken(TOKEN);
        open.setBpmnElementId(handlerId);
        open.setStatus(ActivityStatus.CREATED);
        open.setCreatedAt(THROWN_AT);
        when(dbService.getActiveActivities(PI)).thenReturn(List.of(open));
        when(dbService.getCompletedActivities(PI)).thenReturn(List.of());
        when(dbService.getActivitiesByTokenAndBpmnElementId(any(), any())).thenReturn(List.of());
    }

    @Test
    void targetCompletedInTheSameMillisecondAsTheThrow_staysOwned_andKeepsWaiting() {
        // one target, completed EXACTLY at the thrower's row creation time
        Activity target = completed(TOKEN, "work", THROWN_AT);
        handlerOpen("workHandler");

        assertThat(elementSupport.compensationThrowerHasPending(PI, THROWER,
                model("work", "workHandler"), List.of(target)))
            .as("same-millisecond target is owned (fail-closed): the thrower keeps waiting")
            .isTrue();
    }

    @Test
    void targetCompletedOneMillisecondAfterTheThrow_isCut_andNoLongerWaits() {
        // the drift case: the target finished AFTER the throw, its handler was
        // never launched — the thrower must not wait for a handler that cannot exist
        // no DB read is expected at all: the target is cut before any handler lookup,
        // which is precisely the point of the cut (and strict Mockito proves it)
        Activity late = completed(TOKEN, "work", THROWN_AT.plusMillis(1));

        assertThat(elementSupport.compensationThrowerHasPending(PI, THROWER,
                model("work", "workHandler"), List.of(late)))
            .as("a target completed after the throw is not this thrower's target")
            .isFalse();
    }

    @Test
    void sameMillisecondOwned_targetWaits_evenWhenALaterTargetIsCut() {
        // both targets present: the owned one still decides the answer. Guards against
        // a "cut everything that is not strictly earlier" implementation, which would
        // release the thrower one millisecond early and skip a live compensation.
        Activity owned = completed(TOKEN, "work", THROWN_AT);
        Activity late = completed(TOKEN, "later", THROWN_AT.plusMillis(1));
        handlerOpen("workHandler");

        BpmnProcessDefinitionModel bpmn = model("work", "workHandler");
        BpmnElementModel laterBoundary = new BpmnElementModel();
        laterBoundary.setId("laterBoundary");
        laterBoundary.setType(BpmnElementType.COMPENSATION_BOUNDARY_EVENT);
        BoundaryEventExtensionModel lext = new BoundaryEventExtensionModel();
        lext.setAttachedToRef("later");
        lext.setCompensationHandlerId("laterHandler");
        BpmnElementExtensionModel lmext = new BpmnElementExtensionModel();
        lmext.setBoundaryEventExtension(lext);
        laterBoundary.setExtensions(lmext);
        bpmn.addElement(laterBoundary);
        BpmnElementModel laterHandler = new BpmnElementModel();
        laterHandler.setId("laterHandler");
        laterHandler.setType(BpmnElementType.SERVICE_TASK);
        bpmn.addElement(laterHandler);

        assertThat(elementSupport.compensationThrowerHasPending(PI, THROWER, bpmn, List.of(owned, late)))
            .as("the owned target's open handler keeps the thrower parked")
            .isTrue();
    }
}