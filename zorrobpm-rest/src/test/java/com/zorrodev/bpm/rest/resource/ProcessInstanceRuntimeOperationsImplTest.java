package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.ProcessInstanceLifecycleService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessInstanceRuntimeOperationsImplTest {

    @Mock private RuntimeService runtimeService;
    @Mock private ProcessInstanceLifecycleService processInstanceLifecycleService;
    @Mock private FormArtifactService formArtifactService;
    @Mock private DBService dbService;
    @Mock private com.zorrodev.bpm.engine.handler.CancelingPhaseService cancelingPhaseService;
    @Mock private AuditLogService auditLogService;
    @Mock private RuntimeSupportService runtimeSupportService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @InjectMocks private ProcessInstanceRuntimeOperationsImpl impl;

    @Test
    void startProcessInstance_happyPath() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("key");
        dto.setVariables(List.of());
        String definitionKey = "key";
        when(processInstanceLifecycleService.resolveDefinitionKey(any())).thenReturn("key");
        ProcessDefinitionEntity targetDef = new ProcessDefinitionEntity();
        targetDef.setKey("key");
        targetDef.setStartFormKey(null);
        doNothing().when(runtimeOperationSupport).requireOperate(definitionKey, AuthorizationService.Action.START);
        // Actually requireOperate is void, so doNothing
        // We need to mock the void method
        // The previous when().thenReturn is not needed, we should use doNothing
        // But we already stubbed, let's use doNothing
        // For this test, we will just verify that it doesn't throw
        // Let's set up the rest
        when(runtimeSupportService.resolveTargetDefinition(dto)).thenReturn(targetDef);
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        com.zorrodev.bpm.engine.dto.IdDTO engineId = new com.zorrodev.bpm.engine.dto.IdDTO();
        UUID id = UUID.randomUUID();
        engineId.setId(id);
        // WO-API-1 (API-7): фасад зовёт startProcessInstance(dto, claimed=null).
        when(runtimeService.startProcessInstance(eq(dto), isNull())).thenReturn(engineId);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.toDTO(engineId)).thenReturn(expected);

        IdDTO result = impl.startProcessInstance(dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeOperationSupport).requireOperate(definitionKey, AuthorizationService.Action.START);
        verify(runtimeSupportService).resolveTargetDefinition(dto);
        verify(runtimeService).startProcessInstance(eq(dto), isNull());
        verify(auditLogService).record(principal, "START", definitionKey, id.toString(), null);
    }

    @Test
    void startProcessInstance_denyWhenNotAuthorized() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("key");
        when(processInstanceLifecycleService.resolveDefinitionKey(any())).thenReturn("key");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate("key", AuthorizationService.Action.START);

        assertThatThrownBy(() -> impl.startProcessInstance(dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).startProcessInstance(any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void startProcessInstance_validationFails() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("key");
        when(processInstanceLifecycleService.resolveDefinitionKey(any())).thenReturn("key");
        ProcessDefinitionEntity targetDef = new ProcessDefinitionEntity();
        targetDef.setStartFormKey("formKey");
        when(runtimeSupportService.resolveTargetDefinition(dto)).thenReturn(targetDef);
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        when(formArtifactService.validateFormIfApplicable("formKey", dto.getVariables()))
            .thenReturn(List.of(new FormValidator.ValidationError("field", "error")));

        assertThatThrownBy(() -> impl.startProcessInstance(dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void cancelProcessInstance_happyPath() {
        UUID id = UUID.randomUUID();
        com.zorrodev.bpm.contract.model.ProcessInstance pi = new com.zorrodev.bpm.contract.model.ProcessInstance();
        pi.setId(id);
        pi.setCompletedAt(null);
        // Use mock for isCancelled via real object? Let's mock DBService to return a mock
        when(dbService.getProcessInstance(id)).thenReturn(pi);
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        IdDTO result = impl.cancelProcessInstance(id);

        assertThat(result.getId()).isEqualTo(id);
        verify(dbService).getProcessInstance(id);
        verify(runtimeOperationSupport).resolveDefinitionKeyByInstance(id);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);
        verify(dbService).cancelActiveActivities(id);
        verify(dbService).deleteTimerJobsByProcessInstanceId(id);
        verify(dbService).deleteMessageSubscriptionsByProcessInstanceId(id);
        verify(dbService).cancelProcessInstance(id);
        verify(auditLogService).record(principal, "CANCEL", key, id.toString());
    }

    @Test
    void cancelProcessInstance_cancelingPhaseOpen_defersStatusTail() {
        // WO-C8-28: открытая canceling-фаза откладывает только статус-хвост
        // (cancelProcessInstance); удаление job/subscription идёт сразу, аудит — тоже
        // (фиксирует запрошенную операцию). Статус выставит resume последнего листенера.
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        com.zorrodev.bpm.contract.model.ProcessInstance pi = new com.zorrodev.bpm.contract.model.ProcessInstance();
        pi.setId(id);
        pi.setCompletedAt(null);
        when(dbService.getProcessInstance(id)).thenReturn(pi);
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(cancelingPhaseService.activeUserTaskIdsInInstance(id)).thenReturn(java.util.List.of(taskId));
        when(cancelingPhaseService.openForInstanceSnapshot(id, java.util.List.of(taskId))).thenReturn(true);

        IdDTO result = impl.cancelProcessInstance(id);

        assertThat(result.getId()).isEqualTo(id);
        verify(dbService).deleteTimerJobsByProcessInstanceId(id);
        verify(dbService).deleteMessageSubscriptionsByProcessInstanceId(id);
        verify(dbService, never()).cancelProcessInstance(any());
        verify(auditLogService).record(principal, "CANCEL", key, id.toString());
    }

    @Test
    void cancelProcessInstance_alreadyCompleted_throwsConflict() {
        UUID id = UUID.randomUUID();
        com.zorrodev.bpm.contract.model.ProcessInstance pi = new com.zorrodev.bpm.contract.model.ProcessInstance();
        pi.setId(id);
        pi.setCompletedAt(java.time.Instant.now());
        when(dbService.getProcessInstance(id)).thenReturn(pi);

        assertThatThrownBy(() -> impl.cancelProcessInstance(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(runtimeOperationSupport, never()).resolveDefinitionKeyByInstance(any());
        verify(dbService, never()).cancelProcessInstance(any());
    }

    @Test
    void cancelProcessInstance_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        com.zorrodev.bpm.contract.model.ProcessInstance pi = new com.zorrodev.bpm.contract.model.ProcessInstance();
        pi.setId(id);
        pi.setCompletedAt(null);
        when(dbService.getProcessInstance(id)).thenReturn(pi);
        String key = "key";
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(id)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);

        assertThatThrownBy(() -> impl.cancelProcessInstance(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(dbService, never()).cancelActiveActivities(any());
    }
}
