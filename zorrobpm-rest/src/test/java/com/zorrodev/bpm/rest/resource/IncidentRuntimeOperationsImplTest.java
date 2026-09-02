package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentRuntimeOperationsImplTest {

    @Mock private RuntimeService runtimeService;
    @Mock private AuditLogService auditLogService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @InjectMocks private IncidentRuntimeOperationsImpl impl;

    @Test
    void resolveIncident_happyPath_callsResolveAndAudit() {
        UUID id = UUID.randomUUID();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());
        String key = "myKey";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        com.zorrodev.bpm.engine.dto.IdDTO engineResult = new com.zorrodev.bpm.engine.dto.IdDTO();
        engineResult.setId(id);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(id)).thenReturn(key);
        // requireOperate should not throw
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        when(runtimeService.resolveIncident(id, dto.getVariables())).thenReturn(engineResult);
        when(runtimeOperationSupport.toDTO(engineResult)).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        IdDTO result = impl.resolveIncident(id, dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeOperationSupport).resolveDefinitionKeyByIncident(id);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        verify(runtimeService).resolveIncident(id, dto.getVariables());
        verify(runtimeOperationSupport).toDTO(engineResult);
        verify(auditLogService).record(principal, "RESOLVE_INCIDENT", key, id.toString());
    }

    @Test
    void resolveIncident_denyPath_doesNotCallRuntimeServiceOrAudit() {
        UUID id = UUID.randomUUID();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());
        String key = "myKey";
        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(id)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.resolveIncident(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeOperationSupport).resolveDefinitionKeyByIncident(id);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        verify(runtimeService, never()).resolveIncident(any(), any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
        verify(runtimeOperationSupport, never()).toDTO(any());
        verify(runtimeOperationSupport, never()).getPrincipal();
    }

    @Test
    void resolveIncident_notFound_returns404() {
        UUID id = UUID.randomUUID();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());
        // resolve returns null -> requireOperate(null, ...) -> 404
        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(id)).thenReturn(null);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"))
            .when(runtimeOperationSupport).requireOperate(null, AuthorizationService.Action.COMPLETE_SERVICE_TASK);

        assertThatThrownBy(() -> impl.resolveIncident(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verify(runtimeOperationSupport).resolveDefinitionKeyByIncident(id);
        verify(runtimeOperationSupport).requireOperate(null, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        verify(runtimeService, never()).resolveIncident(any(), any());
    }

    @Test
    void resolveIncident_auditUsesPrincipal() {
        UUID id = UUID.randomUUID();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "bob", "USER");
        com.zorrodev.bpm.engine.dto.IdDTO engineResult = new com.zorrodev.bpm.engine.dto.IdDTO();
        engineResult.setId(id);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(id)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        when(runtimeService.resolveIncident(any(), any())).thenReturn(engineResult);
        when(runtimeOperationSupport.toDTO(any())).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        impl.resolveIncident(id, dto);

        verify(auditLogService).record(principal, "RESOLVE_INCIDENT", key, id.toString());
    }
}
