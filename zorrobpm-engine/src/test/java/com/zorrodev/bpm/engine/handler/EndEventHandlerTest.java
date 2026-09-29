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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndEventHandlerTest {

    @Mock private DBService dbService;
    @Mock private ActivityService activityService;

    private EndEventHandler.EndEvent endEvent;
    private EndEventHandler.TerminateEndEvent terminateEndEvent;
    private EndEventHandler.ErrorEndEvent errorEndEvent;
    private EndEventHandler.EscalationEndEvent escalationEndEvent;

    @BeforeEach
    void setUp() {
        endEvent = new EndEventHandler.EndEvent(dbService, activityService);
        terminateEndEvent = new EndEventHandler.TerminateEndEvent(dbService);
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

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        endEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(activityService).finishBranch(piId, tokenId, bpmn);
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

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        terminateEndEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(dbService).cancelActiveActivities(piId);
        verify(dbService).completeProcessInstance(piId);
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
