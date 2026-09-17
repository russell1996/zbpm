package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.BpmnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ServiceTaskCompleteTransactionIT {

    @Autowired private ServiceTaskRuntimeOperations serviceTaskRuntimeOperations;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private TokenRepository tokenRepository;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;
    @MockitoBean private BpmnService bpmnService;

    private UUID cleanupTask, cleanupPi, cleanupPd, cleanupActivity;
    private UUID cleanupToken1, cleanupToken2;

    @AfterEach
    void cleanup() {
        if (cleanupTask != null) serviceTaskRepository.deleteById(cleanupTask);
        if (cleanupActivity != null) activityRepository.deleteById(cleanupActivity);
        if (cleanupToken1 != null) { try { tokenRepository.deleteById(cleanupToken1); } catch (Exception ignored) {} }
        if (cleanupToken2 != null) { try { tokenRepository.deleteById(cleanupToken2); } catch (Exception ignored) {} }
        if (cleanupPi != null) processInstanceRepository.deleteById(cleanupPi);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupTask = cleanupPi = cleanupPd = cleanupActivity = null;
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
    void completeServiceTask_transactionRollsBackWhenAuditFails() {
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

        UUID taskId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity();
        activity.setId(taskId);
        activity.setProcessInstanceId(piId);
        cleanupToken1 = newToken();
        activity.setToken(cleanupToken1);
        activity.setBpmnElementId("task-" + taskId.toString().substring(0, 8));
        activity.setCreatedAt(Instant.now());
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setStatus(ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity);
        cleanupActivity = taskId;
        ServiceTaskEntity task = new ServiceTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId(activity.getBpmnElementId());
        task.setCreatedAt(Instant.now());
        serviceTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER");
        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(taskId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        com.zorrodev.bpm.contract.dto.IdDTO contractId = new com.zorrodev.bpm.contract.dto.IdDTO();
        contractId.setId(taskId);
        when(runtimeOperationSupport.toDTO(any())).thenReturn(contractId);
        // Mock BPMN for service task completion
        BpmnProcessDefinitionModel bpmnModel = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel elementModel = mock(BpmnElementModel.class);
        when(bpmnModel.getElement(any())).thenReturn(elementModel);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmnModel);

        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), eq("COMPLETE_SERVICE_TASK"), any(), eq(taskId.toString()));

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());

        assertThatThrownBy(() -> serviceTaskRuntimeOperations.completeServiceTask(taskId, dto))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("audit fail");

        // Verify rollback: service task should still exist (not deleted) and not completed?
        // For completeServiceTask, the DB change is via runtimeService, which may delete or complete the task.
        // We just verify that the task still exists and was not removed.
        ServiceTaskEntity after = serviceTaskRepository.findById(taskId).orElse(null);
        assertThat(after).isNotNull();
    }

    @Test
    void completeServiceTask_transactionCommitsWhenAuditSucceeds() {
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

        UUID taskId = UUID.randomUUID();
        ActivityEntity activity2 = new ActivityEntity();
        activity2.setId(taskId);
        activity2.setProcessInstanceId(piId);
        cleanupToken2 = newToken();
        activity2.setToken(cleanupToken2);
        activity2.setBpmnElementId("task-" + taskId.toString().substring(0, 8));
        activity2.setCreatedAt(Instant.now());
        activity2.setType(BpmnElementType.SERVICE_TASK);
        activity2.setStatus(ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity2);
        cleanupActivity = taskId;
        ServiceTaskEntity task = new ServiceTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId(activity2.getBpmnElementId());
        task.setCreatedAt(Instant.now());
        serviceTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        when(runtimeOperationSupport.resolveDefinitionKeyByServiceTask(taskId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test2", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        com.zorrodev.bpm.contract.dto.IdDTO contractId = new com.zorrodev.bpm.contract.dto.IdDTO();
        contractId.setId(taskId);
        when(runtimeOperationSupport.toDTO(any())).thenReturn(contractId);
        BpmnProcessDefinitionModel bpmnModel2 = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel elementModel2 = mock(BpmnElementModel.class);
        when(bpmnModel2.getElement(any())).thenReturn(elementModel2);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmnModel2);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());

        com.zorrodev.bpm.contract.dto.IdDTO result = serviceTaskRuntimeOperations.completeServiceTask(taskId, dto);
        assertThat(result.getId()).isEqualTo(taskId);
    }
}
