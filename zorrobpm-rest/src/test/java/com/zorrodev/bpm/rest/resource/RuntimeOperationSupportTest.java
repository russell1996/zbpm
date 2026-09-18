package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import jakarta.servlet.http.HttpServletRequest;
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
class RuntimeOperationSupportTest {

    @Mock private HttpServletRequest request;
    @Mock private AuthorizationService authorizationService;
    @Mock private RuntimeSupportService runtimeSupportService;

    @InjectMocks private RuntimeOperationSupport support;

    // getPrincipal
    @Test
    void getPrincipal_returnsPrincipalWhenPresent() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        assertThat(support.getPrincipal()).isEqualTo(p);
    }

    @Test
    void getPrincipal_returnsNullWhenAbsent() {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThat(support.getPrincipal()).isNull();
    }

    @Test
    void getPrincipal_returnsNullWhenWrongType() {
        when(request.getAttribute("principal")).thenReturn("not a principal");
        assertThat(support.getPrincipal()).isNull();
    }

    // rawOnBehalfOf
    @Test
    void rawOnBehalfOf_returnsTrimmed() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("  bob  ");
        assertThat(support.rawOnBehalfOf()).isEqualTo("bob");
    }

    @Test
    void rawOnBehalfOf_returnsNullWhenBlank() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("   ");
        assertThat(support.rawOnBehalfOf()).isNull();
    }

    @Test
    void rawOnBehalfOf_truncatesAt255() {
        String longVal = "a".repeat(300);
        when(request.getHeader("X-On-Behalf-Of")).thenReturn(longVal);
        assertThat(support.rawOnBehalfOf()).hasSize(255);
    }

    // checkedOnBehalfOf
    @Test
    void checkedOnBehalfOf_returnsRawWhenServicePrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        assertThat(support.checkedOnBehalfOf()).isEqualTo("bob");
    }

    @Test
    void checkedOnBehalfOf_throwsWhenNotServicePrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        Principal.UserPrincipal up = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(up);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkedOnBehalfOf_throwsWhenNoPrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        when(request.getAttribute("principal")).thenReturn(null);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void checkedOnBehalfOf_returnsNullWhenNoHeader() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn(null);
        assertThat(support.checkedOnBehalfOf()).isNull();
    }

    // WO-SEC-64 (S-RBAC-3): format gate — garbage never travels further

    @Test
    void checkedOnBehalfOf_rejectsGarbageFormat() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("!!!not-a-user!!!");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkedOnBehalfOf_rejectsTooLong() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("a".repeat(65));
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    // WO-SEC-64 HOLD (S-RBAC-3, existence): ghost проходит regex, но такого
    // юзера нет — сервис отвечает 404 (fail-closed до любой работы).

    @Test
    void checkedOnBehalfOf_rejectsGhostUsername() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("ghost-abc123");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "X-On-Behalf-Of user not found"))
            .when(runtimeSupportService).requireOnBehalfExists("ghost-abc123");
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void checkedOnBehalfOf_acceptsLiveUsername() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("admin");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        // exists — mock void-метода молчит
        assertThat(support.checkedOnBehalfOf()).isEqualTo("admin");
    }

    @Test
    void checkedOnBehalfOf_acceptsEmailStyle() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob@example.com");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        assertThat(support.checkedOnBehalfOf()).isEqualTo("bob@example.com");
    }

    // requireOperate
    @Test
    void requireOperate_allowsWhenCanOperate() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        when(authorizationService.canOperate(p, "myKey", AuthorizationService.Action.START)).thenReturn(true);
        support.requireOperate("myKey", AuthorizationService.Action.START);
        verify(authorizationService).canOperate(p, "myKey", AuthorizationService.Action.START);
    }

    @Test
    void requireOperate_deniesWhenCannotOperate() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        when(authorizationService.canOperate(p, "myKey", AuthorizationService.Action.START)).thenReturn(false);
        assertThatThrownBy(() -> support.requireOperate("myKey", AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOperate_throwsWhenNoPrincipal() {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThatThrownBy(() -> support.requireOperate("key", AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requireOperate_throwsWhenDefinitionKeyNull() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        assertThatThrownBy(() -> support.requireOperate(null, AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    // resolveTargetDefinition moved to engine RuntimeSupportServiceTest (WO-DEBT-7 S1)

    // resolveDefinitionKeyByInstance (delegates to RuntimeSupportService)
    @Test
    void resolveDefinitionKeyByInstance_found() {
        UUID instanceId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByInstance(instanceId)).thenReturn("myKey");
        assertThat(support.resolveDefinitionKeyByInstance(instanceId)).isEqualTo("myKey");
    }

    @Test
    void resolveDefinitionKeyByInstance_notFound() {
        UUID instanceId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByInstance(instanceId)).thenReturn(null);
        assertThat(support.resolveDefinitionKeyByInstance(instanceId)).isNull();
    }

    // resolveDefinitionKeyByServiceTask (delegates to RuntimeSupportService)
    @Test
    void resolveDefinitionKeyByServiceTask_found() {
        UUID taskId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByServiceTask(taskId)).thenReturn("key");
        assertThat(support.resolveDefinitionKeyByServiceTask(taskId)).isEqualTo("key");
    }

    @Test
    void resolveDefinitionKeyByServiceTask_notFound() {
        UUID taskId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByServiceTask(taskId)).thenReturn(null);
        assertThat(support.resolveDefinitionKeyByServiceTask(taskId)).isNull();
    }

    // resolveDefinitionKeyByIncident (delegates to RuntimeSupportService)
    @Test
    void resolveDefinitionKeyByIncident_found() {
        UUID incidentId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByIncident(incidentId)).thenReturn("key");
        assertThat(support.resolveDefinitionKeyByIncident(incidentId)).isEqualTo("key");
    }

    @Test
    void resolveDefinitionKeyByIncident_notFound() {
        UUID incidentId = UUID.randomUUID();
        when(runtimeSupportService.resolveDefinitionKeyByIncident(incidentId)).thenReturn(null);
        assertThat(support.resolveDefinitionKeyByIncident(incidentId)).isNull();
    }

    // resolvePrincipalId
    @Test
    void resolvePrincipalId_userPrincipal() {
        Principal.UserPrincipal up = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        assertThat(support.resolvePrincipalId(up)).isEqualTo("alice");
    }

    @Test
    void resolvePrincipalId_servicePrincipal() {
        UUID apiKeyId = UUID.randomUUID();
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(apiKeyId, UUID.randomUUID(), Map.of());
        assertThat(support.resolvePrincipalId(sp)).isEqualTo(apiKeyId.toString());
    }

    // checkAssignee + requireOnBehalfMatchesTask moved to engine RuntimeSupportServiceTest (WO-DEBT-7 S1)

    // toDTO
    @Test
    void toDTO_mapsId() {
        com.zorrodev.bpm.engine.dto.IdDTO engineDto = new com.zorrodev.bpm.engine.dto.IdDTO();
        UUID id = UUID.randomUUID();
        engineDto.setId(id);
        com.zorrodev.bpm.contract.dto.IdDTO result = support.toDTO(engineDto);
        assertThat(result.getId()).isEqualTo(id);
    }
}
