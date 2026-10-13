package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErrorEscalationThrowerTest {

    @Mock
    private DBService dbService;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private EventTrigger eventTrigger;
    // WO-C8-38 (C38-2): новые зависимости конструктора — моки, иначе scope-walk
    // упадёт NPE на scopeContainment (чистка arrived-строк отменённого scope).
    @Mock
    private ScopeContainment scopeContainment;
    @Mock
    private InclusiveGatewayHandler inclusiveGatewayHandler;
    // WO-C8-41 (F5): ElementSupport.deleteMiBodyState на путях отмены MI-тела
    // (G21: @InjectMocks без этого мока оставлял поле null — MI-ветки NPE).
    @Mock
    private ElementSupport elementSupport;

    @InjectMocks
    private ErrorEscalationThrower errorEscalationThrower;

    @Test
    void throwError_returnsFalseWhenNoBoundaryAndNoParent() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String errorCode = "PROCESSED";

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setParentActivityId(null); // No parent
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        when(bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())).thenReturn(bpmn);

        Token tok = new Token();
        tok.setId(tokenId);
        tok.setParentId(null);
        tok.setScopeActivityId(null);
        when(dbService.getToken(tokenId)).thenReturn(tok);

        TokenExecutor executor = org.mockito.Mockito.mock(TokenExecutor.class);

        // When
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, null, executor);

        // Then
        assertThat(result).isFalse();
        verify(dbService, never()).cancelActiveActivitiesForToken(any());
        verify(dbService, never()).cancelActivity(any());
    }

    @Test
    void throwError_returnsTrueWhenBoundaryMatches() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        String errorCode = "PROCESSED";

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setParentActivityId(null);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        // Error boundary attached to scope
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("errorBoundary1");
        boundary.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("subprocess1");
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setCode("PROCESSED");
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);
        bpmn.addElement(boundary);

        when(bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())).thenReturn(bpmn);

        Token tok = new Token();
        tok.setId(tokenId);
        tok.setParentId(null);
        tok.setScopeActivityId(scopeActivityId);
        when(dbService.getToken(tokenId)).thenReturn(tok);

        Activity scope = new Activity();
        scope.setId(scopeActivityId);
        scope.setBpmnElementId("subprocess1");
        when(dbService.getActivity(scopeActivityId)).thenReturn(scope);

        TokenExecutor executor = org.mockito.Mockito.mock(TokenExecutor.class);

        // When
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, null, executor);

        // Then
        assertThat(result).isTrue();
        verify(dbService).cancelActiveActivitiesForToken(tokenId);
        verify(dbService).cancelActivity(scopeActivityId);
    }

    /**
     * WO-C8-41 (F5/G21): error-граница на MI-подпроцессе гасит все инстансы и
     * сносит состояние тела (deleteMiBodyState). Без мока ElementSupport в
     * конструкторе этот путь — NPE (G21 поймал отсутствие мока: тест Elements
     * зелёный только на non-MI ветках).
     */
    @Test
    void throwError_onMiSubProcess_cancelsInstancesAndDropsBodyState() {
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        UUID entryTokenId = UUID.randomUUID();
        String errorCode = "E-1";

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setParentActivityId(null);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        BpmnElementModel subProc = new BpmnElementModel();
        subProc.setId("miSub");
        subProc.setType(BpmnElementType.SUB_PROCESS);
        BpmnElementExtensionModel subExt = new BpmnElementExtensionModel();
        com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel mi =
            new com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel();
        mi.setInputCollection("=items");
        mi.setInputElement("item");
        mi.setOutputCollection("results");
        mi.setOutputElement("=item");
        subExt.setMultiInstanceExtension(mi);
        subProc.setExtensions(subExt);
        bpmn.addElement(subProc);

        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("errorBoundary1");
        boundary.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("miSub");
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setCode("E-1");
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);
        bpmn.addElement(boundary);

        when(bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())).thenReturn(bpmn);

        Token tok = new Token();
        tok.setId(tokenId);
        tok.setParentId(null);
        tok.setScopeActivityId(scopeActivityId);
        when(dbService.getToken(tokenId)).thenReturn(tok);

        Activity scope = new Activity();
        scope.setId(scopeActivityId);
        scope.setBpmnElementId("miSub");
        scope.setToken(entryTokenId);
        scope.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS);
        Activity sibling = new Activity();
        sibling.setId(siblingId);
        sibling.setBpmnElementId("miSub");
        sibling.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);
        when(dbService.getActivity(scopeActivityId)).thenReturn(scope);
        when(dbService.getActiveActivities(processInstanceId))
            .thenReturn(java.util.List.of(scope, sibling));
        // miSubProcessInnerIds идёт напрямую через enclosingScopeChain, а не
        // через filterActivitiesInScope — стабы внутреннего обхода не нужны
        // (strict-stubs: лишнее здесь — ошибка).

        TokenExecutor executor = org.mockito.Mockito.mock(TokenExecutor.class);

        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, null, executor);

        assertThat(result).isTrue();
        // scope-инстанс бросания входит и в miSubProcessInstanceIds (явный add),
        // и в active-скан того же элемента — гасится дважды (идемпотентный
        // cancel по id; обе строки — тот же инстанс, не дубль работы).
        verify(dbService, org.mockito.Mockito.times(2)).cancelActivity(scopeActivityId);
        verify(dbService).cancelActivity(siblingId);
        // WO-C8-41 (F5): состояние прерванного MI-тела снесено — без этого
        // вызова предсозданный results жил бы дальше (мутация M-F5).
        verify(elementSupport).deleteMiBodyState(processInstanceId, entryTokenId, subProc);
    }

    @Test
    void throwError_catchAllBoundaryMatchesAnyCode() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        String errorCode = "ANY_CODE";

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setParentActivityId(null);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        // Error boundary WITHOUT code (catch-all)
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("errorBoundary1");
        boundary.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("subprocess1");
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setCode(null); // Catch-all
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);
        bpmn.addElement(boundary);

        when(bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())).thenReturn(bpmn);

        Token tok = new Token();
        tok.setId(tokenId);
        tok.setParentId(null);
        tok.setScopeActivityId(scopeActivityId);
        when(dbService.getToken(tokenId)).thenReturn(tok);

        Activity scope = new Activity();
        scope.setId(scopeActivityId);
        scope.setBpmnElementId("subprocess1");
        when(dbService.getActivity(scopeActivityId)).thenReturn(scope);

        TokenExecutor executor = org.mockito.Mockito.mock(TokenExecutor.class);

        // When
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, null, executor);

        // Then
        assertThat(result).isTrue();
    }
}