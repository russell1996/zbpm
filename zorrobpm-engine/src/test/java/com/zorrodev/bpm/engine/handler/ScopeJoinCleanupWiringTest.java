package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-38 (C38-2, раунд 2, решение CTO по эскалации V10, вопрос 2, вариант А;
 * раунд 3, БЛОКИРУЮЩАЯ №1 рецензии r3): чистка arrived-строк join'ов отменённого
 * scope на четырёх путях, которые раунд 1 не покрыл (error scope-walk,
 * interrupting-escalation, terminate in-scope, cancel-end транзакции).
 *
 * <p>Сквозные живые IT этих сайтов —
 * {@code AdhocScopeArrivalCleanupIntegrationTests} 4/4 (ad-hoc scope + мост:
 * чистка — ЖИВОЙ фикс живого сценария, каждая мутация «убрать строку» роняет
 * свой IT). Здесь — unit-доказательство ВЫЗОВА: каждый сайт в своей отменяющей
 * ветке зовёт {@code clearParallelGatewayArrivalsInJoins} с join'ами своего
 * scope в ТОЙ ЖЕ транзакции и БЕЗ резюма (резюм запрещён контрпримером
 * раунда 4 C8-35).
 *
 * <p>P-67: мутация «убрать строку чистки» роняет ровно свой тест (verify фиксирует
 * вызов). Containment ({@code inclusiveGatewayIdsInsideScope}) мокается — его
 * покрывает {@code ScopeContainmentTest} 10/10.
 */
@ExtendWith(MockitoExtension.class)
class ScopeJoinCleanupWiringTest {

    @Mock private DBService dbService;
    @Mock private BpmnService bpmnService;
    @Mock private FlowNavigator flowNavigator;
    @Mock private EventTrigger eventTrigger;
    @Mock private ElementSupport elementSupport;
    @Mock private ActivityService activityService;
    @Mock private CompensationThrowHandler compensationThrowHandler;
    @Mock private ScopeContainment scopeContainment;
    @Mock private InclusiveGatewayHandler inclusiveGatewayHandler;

    private static ProcessInstance instance(UUID pi) {
        ProcessInstance inst = new ProcessInstance();
        inst.setId(pi);
        inst.setParentActivityId(null);
        return inst;
    }

    private static Token scopeToken(UUID tokenId, UUID scopeActivityId, UUID parentTokenId) {
        Token tok = new Token();
        tok.setId(tokenId);
        tok.setScopeActivityId(scopeActivityId);
        tok.setParentId(parentTokenId);
        return tok;
    }

    @Test
    void errorScopeWalk_clearsArrivalsOfTheCancelledScope() {
        UUID pi = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        String errorCode = "E-1";

        when(dbService.getProcessInstance(pi)).thenReturn(instance(pi));

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("errBnd");
        boundary.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("subProc");
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setCode(errorCode);
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);
        bpmn.addElement(boundary);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmn);

        when(dbService.getToken(tokenId)).thenReturn(scopeToken(tokenId, scopeActivityId, null));
        Activity scope = new Activity();
        scope.setId(scopeActivityId);
        scope.setBpmnElementId("subProc");
        when(dbService.getActivity(scopeActivityId)).thenReturn(scope);
        when(scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, "subProc"))
            .thenReturn(List.of("joinIn"));

        TokenExecutor executor = mock(TokenExecutor.class);

        ErrorEscalationThrower thrower = new ErrorEscalationThrower(dbService, bpmnService,
            flowNavigator, eventTrigger, scopeContainment, inclusiveGatewayHandler);
        assertThat(thrower.throwError(pi, tokenId, errorCode, null, executor)).isTrue();

        verify(dbService).clearParallelGatewayArrivalsInJoins(eq(pi), eq(List.of("joinIn")));
    }

    @Test
    void interruptingEscalation_clearsArrivalsOfTheCancelledScope() {
        UUID pi = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        String escalationCode = "ESC-1";

        when(dbService.getProcessInstance(pi)).thenReturn(instance(pi));

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("escBnd");
        boundary.setType(BpmnElementType.ESCALATION_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("subProc");
        boundaryExt.setInterrupting(true);
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setCode(escalationCode);
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);
        bpmn.addElement(boundary);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmn);

        when(dbService.getToken(tokenId)).thenReturn(scopeToken(tokenId, scopeActivityId, null));
        Activity scope = new Activity();
        scope.setId(scopeActivityId);
        scope.setBpmnElementId("subProc");
        when(dbService.getActivity(scopeActivityId)).thenReturn(scope);
        when(scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, "subProc"))
            .thenReturn(List.of("joinIn"));

        TokenExecutor executor = mock(TokenExecutor.class);

        ErrorEscalationThrower thrower = new ErrorEscalationThrower(dbService, bpmnService,
            flowNavigator, eventTrigger, scopeContainment, inclusiveGatewayHandler);
        assertThat(thrower.throwEscalation(pi, tokenId, escalationCode, executor)).isTrue();

        verify(dbService).clearParallelGatewayArrivalsInJoins(eq(pi), eq(List.of("joinIn")));
    }

    @Test
    void terminateInScope_clearsArrivalsOfTheCancelledScope() {
        UUID pi = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID parentTokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        BpmnElementModel el = new BpmnElementModel();
        el.setId("termEnd");
        el.setType(BpmnElementType.TERMINATE_END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel scopeElement = new BpmnElementModel();
        scopeElement.setId("subProc");
        scopeElement.setType(BpmnElementType.SUB_PROCESS);
        bpmn.addElement(scopeElement);

        when(dbService.createActivity(eq(pi), eq(tokenId), eq(el))).thenReturn(activityId);
        Token token = mock(Token.class);
        when(token.getScopeActivityId()).thenReturn(scopeActivityId);
        when(token.getParentId()).thenReturn(parentTokenId);
        when(dbService.findToken(eq(tokenId))).thenReturn(Optional.of(token));
        when(dbService.getActiveActivities(eq(pi))).thenReturn(List.of());
        when(elementSupport.filterActivitiesInScope(eq(pi), any(), eq(scopeActivityId)))
            .thenReturn(List.of());
        Activity scope = mock(Activity.class);
        when(scope.getBpmnElementId()).thenReturn("subProc");
        when(dbService.getActivity(eq(scopeActivityId))).thenReturn(scope);
        when(scopeContainment.inclusiveGatewayIdsInsideScope(eq(bpmn), eq("subProc")))
            .thenReturn(List.of("joinIn"));

        EndEventHandler.TerminateEndEvent terminate =
            new EndEventHandler.TerminateEndEvent(dbService, flowNavigator, elementSupport, scopeContainment);
        terminate.handle(new ExecutionCtx(pi, tokenId, mock(TokenExecutor.class), null), bpmn, el);

        verify(dbService).cancelActivity(eq(scopeActivityId));
        verify(dbService).clearParallelGatewayArrivalsInJoins(eq(pi), eq(List.of("joinIn")));
    }

    @Test
    void cancelEndInTransaction_clearsArrivalsOfTheCancelledScope() {
        UUID pi = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID parentTokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        BpmnElementModel el = new BpmnElementModel();
        el.setId("cancelEnd");
        el.setType(BpmnElementType.CANCEL_END_EVENT);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel transaction = new BpmnElementModel();
        transaction.setId("tx");
        transaction.setType(BpmnElementType.SUB_PROCESS);
        bpmn.addElement(transaction);

        when(dbService.createActivity(eq(pi), eq(tokenId), eq(el))).thenReturn(activityId);
        Token token = mock(Token.class);
        when(token.getScopeActivityId()).thenReturn(scopeActivityId);
        when(token.getParentId()).thenReturn(parentTokenId);
        when(dbService.getToken(eq(tokenId))).thenReturn(token);
        Activity scope = mock(Activity.class);
        when(scope.getBpmnElementId()).thenReturn("tx");
        when(dbService.getActivity(eq(scopeActivityId))).thenReturn(scope);
        when(dbService.getCompletedActivities(eq(pi))).thenReturn(List.of());
        when(scopeContainment.inclusiveGatewayIdsInsideScope(eq(bpmn), eq("tx")))
            .thenReturn(List.of("joinIn"));

        CancelEndHandler handler = new CancelEndHandler(dbService, flowNavigator,
            activityService, compensationThrowHandler, scopeContainment);
        handler.handle(new ExecutionCtx(pi, tokenId, mock(TokenExecutor.class), null), bpmn, el);

        verify(dbService).cancelActivity(eq(scopeActivityId));
        verify(dbService).clearParallelGatewayArrivalsInJoins(eq(pi), eq(List.of("joinIn")));
    }
}
