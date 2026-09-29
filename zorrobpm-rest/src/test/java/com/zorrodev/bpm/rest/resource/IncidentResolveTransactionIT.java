package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.zorrodev.bpm.engine.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class IncidentResolveTransactionIT {

    @Autowired private IncidentRuntimeOperations incidentRuntimeOperations;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private TokenRepository tokenRepository;

    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;

    private UUID cleanupIncident, cleanupActivity, cleanupPi, cleanupPd;
    private UUID cleanupToken1, cleanupToken2;

    @AfterEach
    void cleanup() {
        if (cleanupIncident != null) incidentRepository.deleteById(cleanupIncident);
        if (cleanupActivity != null) activityRepository.deleteById(cleanupActivity);
        if (cleanupToken1 != null) { try { tokenRepository.deleteById(cleanupToken1); } catch (Exception ignored) {} }
        if (cleanupToken2 != null) { try { tokenRepository.deleteById(cleanupToken2); } catch (Exception ignored) {} }
        if (cleanupPi != null) processInstanceRepository.deleteById(cleanupPi);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupIncident = cleanupActivity = cleanupPi = cleanupPd = null;
        cleanupToken1 = cleanupToken2 = null;
    }

    // WO-OPS-12: fk_activities__token — activity ссылается только на существующий token.
    private UUID newToken() {
        UUID tokenId = UUID.randomUUID();
        TokenEntity token = new TokenEntity();
        token.setId(tokenId);
        tokenRepository.saveAndFlush(token);
        return tokenId;
    }

    @Test
    void resolveIncident_transactionRollsBackWhenAuditFails() {
        // Setup: PD -> PI -> Activity -> Incident
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("k-" + pdId.toString().substring(0, 8));
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("test");
        pd.setDeploymentState("ACTIVE");
        processDefinitionRepository.saveAndFlush(pd);
        cleanupPd = pdId;

        UUID piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setInitiator("test");
        processInstanceRepository.saveAndFlush(pi);
        cleanupPi = piId;

        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        activity.setProcessInstanceId(piId);
        cleanupToken1 = newToken();
        activity.setToken(cleanupToken1);
        activity.setBpmnElementId("task-" + activityId.toString().substring(0, 8));
        activity.setCreatedAt(Instant.now());
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setStatus(ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity);
        cleanupActivity = activityId;

        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = new IncidentEntity();
        incident.setId(incidentId);
        incident.setActivityId(activityId);
        incident.setMessage("test");
        incident.setCreatedAt(Instant.now());
        incidentRepository.saveAndFlush(incident);
        cleanupIncident = incidentId;

        // Mock authz to pass
        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(incidentId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        com.zorrodev.bpm.contract.dto.IdDTO contractId = new com.zorrodev.bpm.contract.dto.IdDTO();
        contractId.setId(incidentId);
        when(runtimeOperationSupport.toDTO(any())).thenReturn(contractId);

        // Force audit to fail AFTER runtimeService has written (resolveIncident does DB write then audit)
        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), eq("RESOLVE_INCIDENT"), any(), eq(incidentId.toString()));

        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());

        assertThatThrownBy(() -> incidentRuntimeOperations.resolveIncident(incidentId, dto))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("audit fail");

        // Verify rollback: incident should still exist and not be completed (completedAt null)
        // Need to fetch fresh from DB
        IncidentEntity after = incidentRepository.findById(incidentId).orElse(null);
        assertThat(after).isNotNull();
        // The incident was not marked completed — completedAt should still be null
        // (runtimeService.resolveIncident would set completedAt, but transaction rolled back)
        assertThat(after.getCompletedAt()).isNull();
    }

    @Test
    void resolveIncident_transactionCommitsWhenAuditSucceeds() {
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("k-" + pdId.toString().substring(0, 8));
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("test");
        pd.setDeploymentState("ACTIVE");
        processDefinitionRepository.saveAndFlush(pd);
        cleanupPd = pdId;

        UUID piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setInitiator("test");
        processInstanceRepository.saveAndFlush(pi);
        cleanupPi = piId;

        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        activity.setProcessInstanceId(piId);
        cleanupToken2 = newToken();
        activity.setToken(cleanupToken2);
        activity.setBpmnElementId("task-" + activityId.toString().substring(0, 8));
        activity.setCreatedAt(Instant.now());
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setStatus(ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity);
        cleanupActivity = activityId;

        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = new IncidentEntity();
        incident.setId(incidentId);
        incident.setActivityId(activityId);
        incident.setMessage("test");
        incident.setCreatedAt(Instant.now());
        incidentRepository.saveAndFlush(incident);
        cleanupIncident = incidentId;

        when(runtimeOperationSupport.resolveDefinitionKeyByIncident(incidentId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        com.zorrodev.bpm.contract.dto.IdDTO contractId = new com.zorrodev.bpm.contract.dto.IdDTO();
        contractId.setId(incidentId);
        when(runtimeOperationSupport.toDTO(any())).thenReturn(contractId);

        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(List.of());

        // Should not throw
        com.zorrodev.bpm.contract.dto.IdDTO result = incidentRuntimeOperations.resolveIncident(incidentId, dto);
        assertThat(result.getId()).isEqualTo(incidentId);

        // Verify commit: incident should be completed (completedAt not null)
        IncidentEntity after = incidentRepository.findById(incidentId).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getCompletedAt()).isNotNull();
    }
}
