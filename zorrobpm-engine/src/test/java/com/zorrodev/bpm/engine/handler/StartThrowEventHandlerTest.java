package com.zorrodev.bpm.engine.handler;

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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartThrowEventHandlerTest {

    @Mock private DBService dbService;
    @Mock private ActivityService activityService;
    @Mock private FlowNavigator flowNavigator;

    private StartThrowEventHandler.StartEvent startEvent;
    private StartThrowEventHandler.EscalationThrowEvent escalationThrowEvent;

    @BeforeEach
    void setUp() {
        startEvent = new StartThrowEventHandler.StartEvent(dbService, flowNavigator);
        escalationThrowEvent = new StartThrowEventHandler.EscalationThrowEvent(dbService, flowNavigator, activityService);
    }

    @Test
    void startEvent_createActivityCompleteAndProceedToOutgoing() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("start1");
        el.setType(BpmnElementType.START_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        startEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(flowNavigator).proceedToOutgoing(eq(piId), eq(tokenId), eq(bpmn), eq(el), isNull());
    }

    @Test
    void escalationThrowEvent_notInterruptedCallsProceedToOutgoing() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("escThrow1");
        el.setType(BpmnElementType.ESCALATION_THROW_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(activityService.escalationCode(el)).thenReturn("E-200");
        when(activityService.throwEscalation(piId, tokenId, "E-200")).thenReturn(false);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        escalationThrowEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(activityService).throwEscalation(piId, tokenId, "E-200");
        verify(flowNavigator).proceedToOutgoing(eq(piId), eq(tokenId), eq(bpmn), eq(el), isNull());
    }

    @Test
    void escalationThrowEvent_interruptedDoesNotProceed() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("escThrow2");
        el.setType(BpmnElementType.ESCALATION_THROW_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(activityService.escalationCode(el)).thenReturn("E-300");
        when(activityService.throwEscalation(piId, tokenId, "E-300")).thenReturn(true);

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        escalationThrowEvent.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).completeActivity(activityId);
        verify(activityService).throwEscalation(piId, tokenId, "E-300");
        verify(flowNavigator, never()).proceedToOutgoing(any(), any(), any(), any(), any());
    }
}
