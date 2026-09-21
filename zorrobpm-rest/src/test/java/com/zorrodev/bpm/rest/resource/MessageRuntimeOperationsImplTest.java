package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
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

/**
 * WO-DIFF-5 (P-46 guard wiring): {@code POST /messages/publish} has two authz gates —
 * instance-scoped goes through {@code CORRELATE_MESSAGE} (not COMPLETE_SERVICE_TASK, not
 * unguarded), global is SUPER_ADMIN-only. A rollback of either gate fails here.
 */
@ExtendWith(MockitoExtension.class)
class MessageRuntimeOperationsImplTest {

    @Mock private RuntimeService runtimeService;
    @Mock private AuditLogService auditLogService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @InjectMocks private MessageRuntimeOperationsImpl impl;

    private PublishMessageDTO dto(UUID instanceId) {
        PublishMessageDTO dto = new PublishMessageDTO();
        dto.setMessageName("m");
        dto.setCorrelationKey(null);
        dto.setProcessInstanceId(instanceId);
        dto.setVariables(List.of());
        return dto;
    }

    @Test
    void publishMessage_scoped_usesCorrelateMessageGrant() {
        UUID instanceId = UUID.randomUUID();
        String key = "key";
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        MessagePublishResultDTO expected = new MessagePublishResultDTO();
        expected.setCorrelated(1);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(instanceId)).thenReturn(key);
        doNothing().when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.CORRELATE_MESSAGE);
        when(runtimeService.publishMessage(any())).thenReturn(expected);
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        MessagePublishResultDTO result = impl.publishMessage(dto(instanceId));

        assertThat(result).isEqualTo(expected);
        verify(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.CORRELATE_MESSAGE);
        verify(auditLogService).record(principal, "CORRELATE_MESSAGE", key, instanceId.toString());
    }

    @Test
    void publishMessage_scoped_denyWhenNotAuthorized() {
        UUID instanceId = UUID.randomUUID();
        String key = "key";
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(instanceId)).thenReturn(key);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"))
            .when(runtimeOperationSupport).requireOperate(key, AuthorizationService.Action.CORRELATE_MESSAGE);

        assertThatThrownBy(() -> impl.publishMessage(dto(instanceId)))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).publishMessage(any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }

    @Test
    void publishMessage_global_adminPasses() {
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(admin);
        MessagePublishResultDTO expected = new MessagePublishResultDTO();
        when(runtimeService.publishMessage(any())).thenReturn(expected);

        MessagePublishResultDTO result = impl.publishMessage(dto(null));

        assertThat(result).isEqualTo(expected);
        verify(runtimeService).publishMessage(any());
        verify(auditLogService).record(admin, "CORRELATE_MESSAGE", null, "m");
    }

    @Test
    void publishMessage_global_nonAdmin_403WithoutSideEffects() {
        Principal user = new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(user);

        assertThatThrownBy(() -> impl.publishMessage(dto(null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).publishMessage(any());
        verify(auditLogService, never()).record(any(), any(), any(), any());
    }
}
