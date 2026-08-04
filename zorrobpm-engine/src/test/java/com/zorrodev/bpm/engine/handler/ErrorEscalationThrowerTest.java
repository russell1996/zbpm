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
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, executor);

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
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, executor);

        // Then
        assertThat(result).isTrue();
        verify(dbService).cancelActiveActivitiesForToken(tokenId);
        verify(dbService).cancelActivity(scopeActivityId);
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
        boolean result = errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, executor);

        // Then
        assertThat(result).isTrue();
    }
}