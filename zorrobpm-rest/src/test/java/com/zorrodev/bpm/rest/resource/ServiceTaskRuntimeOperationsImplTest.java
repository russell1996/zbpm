package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceTaskRuntimeOperationsImplTest {

    @Mock private RuntimeService runtimeService;
    @Mock private AuditLogService auditLogService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @InjectMocks private ServiceTaskRuntimeOperationsImpl impl;

    @Test
    void completeServiceTask_happyPath() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        com.zorrodev.bpm.engine.dto.IdDTO engineId = new com.zorrodev.bpm.engine.dto.IdDTO();
        engineId.setId(id);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        when(runtimeService.completeServiceTask(id, dto.getVariables())).thenReturn(engineId);
        when(runtimeOperationSupport.toDTO(engineId)).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        IdDTO result = impl.completeServiceTask(id, dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeOperationSupport).resolveDefinitionKeyByServiceTask(id);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        verify(runtimeService).completeServiceTask(id, dto.getVariables());
        verify(auditLogService).record(principal, "COMPLETE_SERVICE_TASK", key, id.toString());
    }

    @Test
    void completeServiceTask_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        String key = "key";
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.completeServiceTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).completeServiceTask(any(), any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void completeServiceTask_notFoundKey_throws404() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(null);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"))
            .when(runtimeOperationSupport).requireOperate(null, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.completeServiceTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void failServiceTask_happyPath() {
        UUID id = UUID.randomUUID();
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage("msg");
        dto.setRetries(2);
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        com.zorrodev.bpm.engine.dto.IdDTO engineId = new com.zorrodev.bpm.engine.dto.IdDTO();
        engineId.setId(id);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        when(runtimeService.failServiceTask(id, dto.getMessage(), dto.getRetries())).thenReturn(engineId);
        when(runtimeOperationSupport.toDTO(engineId)).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        IdDTO result = impl.failServiceTask(id, dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeService).failServiceTask(id, dto.getMessage(), dto.getRetries());
        verify(auditLogService).record(principal, "FAIL_SERVICE_TASK", key, id.toString());
    }

    @Test
    void failServiceTask_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage("msg");
        dto.setRetries(1);
        String key = "key";
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.failServiceTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void throwServiceTaskError_happyPath_usesCompleteServiceTaskGrant() {
        // WO-DIFF-5 (P-46 guard wiring): throw-error must pass through the same
        // COMPLETE_SERVICE_TASK grant as fail — a rollback to any other action fails here.
        UUID id = UUID.randomUUID();
        ThrowErrorDTO dto = new ThrowErrorDTO();
        dto.setErrorCode("E-1");
        dto.setVariables(List.of());
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        ThrowErrorResultDTO expected = new ThrowErrorResultDTO();
        expected.setHandled(true);
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        when(runtimeService.throwServiceTaskError(id, dto)).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        ThrowErrorResultDTO result = impl.throwServiceTaskError(id, dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        verify(runtimeService).throwServiceTaskError(id, dto);
        verify(auditLogService).record(principal, "THROW_ERROR", key, id.toString());
    }

    @Test
    void throwServiceTaskError_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        ThrowErrorDTO dto = new ThrowErrorDTO();
        dto.setErrorCode("E-1");
        dto.setVariables(List.of());
        String key = "key";
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.throwServiceTaskError(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).throwServiceTaskError(any(), any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }
}
