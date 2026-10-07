package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndEventHandlerTest {

    @Mock private DBService dbService;
    @Mock private ActivityService activityService;
    @Mock private FlowNavigator flowNavigator;
    @Mock private ElementSupport elementSupport;

    private EndEventHandler.EndEvent endEvent;
    private EndEventHandler.TerminateEndEvent terminateEndEvent;
    private EndEventHandler.ErrorEndEvent errorEndEvent;
    private EndEventHandler.EscalationEndEvent escalationEndEvent;

    @BeforeEach
    void setUp() {
        endEvent = new EndEventHandler.EndEvent(dbService, flowNavigator);
        terminateEndEvent = new EndEventHandler.TerminateEndEvent(dbService, flowNavigator, elementSupport);
        errorEndEvent = new EndEventHandler.ErrorEndEvent(dbService, activityService);
        escalationEndEvent = new EndEventHandler.EscalationEndEvent(dbService, activityService);
    }

    @Test
    void endEvent_createActivityAndCompleteAndFinishBranch() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("end1");
        el.setType(BpmnElementType.END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        // WO-C8-37 (C37-1): plain token (no scope) — straight to the historical
        // finishBranch path, the join hook is not consulted.
        com.zorrodev.bpm.engine.dto.Token token = mock(com.zorrodev.bpm.engine.dto.Token.class);
        when(token.getScopeActivityId()).thenReturn(null);
        when(dbService.findToken(eq(tokenId))).thenReturn(Optional.of(token));

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        endEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(flowNavigator, never()).handleAdHocArrival(any(), any(), any(), any(), any());
        verify(flowNavigator).finishBranch(piId, tokenId, bpmn, null);
    }

    @Test
    void endEvent_insideLiveAdhocScope_isJoinArrival() {
        UUID piId = UUID.randomUUID();
        UUID scopeTokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("innerEnd");
        el.setType(BpmnElementType.END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(scopeTokenId), eq(el))).thenReturn(activityId);
        com.zorrodev.bpm.engine.dto.Token token = mock(com.zorrodev.bpm.engine.dto.Token.class);
        when(token.getScopeActivityId()).thenReturn(scopeActivityId);
        when(dbService.findToken(eq(scopeTokenId))).thenReturn(Optional.of(token));
        com.zorrodev.bpm.engine.dto.Activity scope = mock(com.zorrodev.bpm.engine.dto.Activity.class);
        when(scope.getType()).thenReturn(BpmnElementType.AD_HOC_SUB_PROCESS);
        when(scope.getStatus()).thenReturn(com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS);
        when(dbService.getActivity(eq(scopeActivityId))).thenReturn(scope);
        // WO-C8-37 (C37-1): the join consumes the branch here — finishBranch must
        // NOT run (that would repeat the scope continuation); a live scope whose
        // join does NOT finish parks silently (return before finishBranch too).
        when(flowNavigator.handleAdHocArrival(eq(piId), eq(scopeTokenId), eq(bpmn), eq(el), isNull()))
            .thenReturn(com.zorrodev.bpm.engine.handler.ArrivalOutcome.SCOPE_FINISHED);

        ExecutionCtx ctx = new ExecutionCtx(piId, scopeTokenId, null, null);
        endEvent.handle(ctx, bpmn, el);

        verify(flowNavigator).handleAdHocArrival(piId, scopeTokenId, bpmn, el, null);
        verify(flowNavigator, never()).finishBranch(any(), any(), any(), any());
    }

    @Test
    void endEvent_insideLiveAdhocScope_joinNotFinished_parksSilently() {
        UUID piId = UUID.randomUUID();
        UUID scopeTokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("innerEnd");
        el.setType(BpmnElementType.END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(scopeTokenId), eq(el))).thenReturn(activityId);
        com.zorrodev.bpm.engine.dto.Token token = mock(com.zorrodev.bpm.engine.dto.Token.class);
        when(token.getScopeActivityId()).thenReturn(scopeActivityId);
        when(dbService.findToken(eq(scopeTokenId))).thenReturn(Optional.of(token));
        com.zorrodev.bpm.engine.dto.Activity scope = mock(com.zorrodev.bpm.engine.dto.Activity.class);
        when(scope.getType()).thenReturn(BpmnElementType.AD_HOC_SUB_PROCESS);
        when(scope.getStatus()).thenReturn(com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS);
        when(dbService.getActivity(eq(scopeActivityId))).thenReturn(scope);
        // WO-C8-37 (C37-1): join not finished (counter/quiescence say wait) — the
        // inner chain parks; finishBranch must NOT run on the scope token (that
        // would mis-close the live scope off the join's control).
        when(flowNavigator.handleAdHocArrival(eq(piId), eq(scopeTokenId), eq(bpmn), eq(el), isNull()))
            .thenReturn(com.zorrodev.bpm.engine.handler.ArrivalOutcome.CONTINUE);

        ExecutionCtx ctx = new ExecutionCtx(piId, scopeTokenId, null, null);
        endEvent.handle(ctx, bpmn, el);

        verify(flowNavigator).handleAdHocArrival(piId, scopeTokenId, bpmn, el, null);
        verify(flowNavigator, never()).finishBranch(any(), any(), any(), any());
    }

    @Test
    void terminateEndEvent_createActivityCompleteCancelAndCompleteInstance() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("term1");
        el.setType(BpmnElementType.TERMINATE_END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        // WO-C8-34: root-scope token (no enclosing scope) keeps the old whole-instance path.
        when(dbService.findToken(eq(tokenId))).thenReturn(Optional.empty());

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        terminateEndEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(dbService).cancelActiveActivities(piId);
        verify(dbService).completeProcessInstance(piId);
    }

    @Test
    void terminateEndEvent_inSubprocessScope_cancelsOnlyScopeAndContinuesParent() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID parentTokenId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("term1");
        el.setType(BpmnElementType.TERMINATE_END_EVENT);
        BpmnProcessDefinitionModel bpmn = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel scopeElement = mock(BpmnElementModel.class);

        com.zorrodev.bpm.engine.dto.Token token = mock(com.zorrodev.bpm.engine.dto.Token.class);
        when(token.getScopeActivityId()).thenReturn(scopeActivityId);
        when(token.getParentId()).thenReturn(parentTokenId);
        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(dbService.findToken(eq(tokenId))).thenReturn(Optional.of(token));

        com.zorrodev.bpm.engine.dto.Activity inner = mock(com.zorrodev.bpm.engine.dto.Activity.class);
        UUID innerId = UUID.randomUUID();
        when(inner.getId()).thenReturn(innerId);
        com.zorrodev.bpm.engine.dto.Activity outer = mock(com.zorrodev.bpm.engine.dto.Activity.class);
        UUID outerId = UUID.randomUUID();
        java.util.List<com.zorrodev.bpm.engine.dto.Activity> active = java.util.List.of(inner, outer);
        when(dbService.getActiveActivities(eq(piId))).thenReturn(active);
        when(elementSupport.filterActivitiesInScope(eq(piId), eq(active), eq(scopeActivityId)))
            .thenReturn(java.util.List.of(inner));

        com.zorrodev.bpm.engine.dto.Activity scope = mock(com.zorrodev.bpm.engine.dto.Activity.class);
        when(scope.getBpmnElementId()).thenReturn("sub1");
        when(dbService.getActivity(eq(scopeActivityId))).thenReturn(scope);
        when(bpmn.getElement(eq("sub1"))).thenReturn(scopeElement);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        terminateEndEvent.handle(ctx, bpmn, el);

        // scope-confined: no whole-instance cancel/complete …
        verify(dbService, never()).cancelActiveActivities(eq(piId));
        verify(dbService, never()).completeProcessInstance(eq(piId));
        // … only the in-scope activity plus the container are cancelled …
        verify(dbService).cancelActivity(eq(innerId));
        verify(dbService, never()).cancelActivity(eq(outerId));
        verify(dbService).cancelActivity(eq(scopeActivityId));
        // … and the parent flow continues from the subprocess outgoing.
        verify(flowNavigator).proceedToOutgoing(eq(piId), eq(parentTokenId), eq(bpmn), eq(scopeElement), any());
    }

    @Test
    void errorEndEvent_unhandledCreatesIncident() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("errEnd1");
        el.setType(BpmnElementType.ERROR_END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(activityService.throwError(piId, tokenId, null)).thenReturn(false);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        errorEndEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(activityService).throwError(piId, tokenId, null);
        verify(dbService).errorActivity(activityId);
        verify(dbService).createIncident(eq(activityId), any());
    }

    @Test
    void escalationEndEvent_notInterruptedCallsFinishBranch() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("escEnd1");
        el.setType(BpmnElementType.ESCALATION_END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(activityService.escalationCode(el)).thenReturn("E-100");
        when(activityService.throwEscalation(piId, tokenId, "E-100")).thenReturn(false);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        escalationEndEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(activityService).throwEscalation(piId, tokenId, "E-100");
        verify(activityService).finishBranch(piId, tokenId, bpmn);
    }
}
