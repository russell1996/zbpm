package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
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
class UserTaskCompleteTransactionIT {

    @Autowired private UserTaskRuntimeOperations userTaskRuntimeOperations;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private TokenRepository tokenRepository;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;
    @MockitoBean private AuthorizationService authorizationService;
    @MockitoBean private BpmnService bpmnService;

    private UUID cleanupTask, cleanupPi, cleanupPd;
    private UUID cleanupToken1, cleanupToken2;

    @AfterEach
    void cleanup() {
        if (cleanupTask != null) {
            try { userTaskRepository.deleteById(cleanupTask); } catch (Exception ignored) {}
            try { activityRepository.deleteById(cleanupTask); } catch (Exception ignored) {}
        }
        if (cleanupToken1 != null) { try { tokenRepository.deleteById(cleanupToken1); } catch (Exception ignored) {} }
        if (cleanupToken2 != null) { try { tokenRepository.deleteById(cleanupToken2); } catch (Exception ignored) {} }
        if (cleanupPi != null) processInstanceRepository.deleteById(cleanupPi);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupTask = cleanupPi = cleanupPd = null;
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
    void completeUserTask_transactionRollsBackWhenAuditFails() {
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
        activity.setType(com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK);
        activity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity);
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId(activity.getBpmnElementId());
        task.setCreatedAt(Instant.now());
        task.setCandidateGroups("group1");
        task.setFormKey(null);
        userTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        // Mock authz to pass
        com.zorrodev.bpm.engine.security.Principal principal = new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(UUID.randomUUID(), "test", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        lenient().when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        doNothing().when(runtimeOperationSupport).requireOnBehalfMatchesTask(any(), any(), any(), any());
        doNothing().when(runtimeOperationSupport).checkAssignee(any(), any(), any(), any());
        when(authorizationService.canCompleteUserTask(any(), any(), any())).thenReturn(true);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        BpmnProcessDefinitionModel bpmnModel = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel elementModel = mock(BpmnElementModel.class);
        when(bpmnModel.getElement(any())).thenReturn(elementModel);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmnModel);
        lenient().when(runtimeOperationSupport.toDTO(any())).thenAnswer(inv -> {
            com.zorrodev.bpm.contract.dto.IdDTO dto = new com.zorrodev.bpm.contract.dto.IdDTO();
            dto.setId(taskId);
            return dto;
        });

        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), eq("COMPLETE_USER_TASK"), any(), eq(taskId.toString()), any());

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());

        assertThatThrownBy(() -> userTaskRuntimeOperations.completeUserTask(taskId, dto))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("audit fail");

        // Verify rollback: task should still be not completed
        UserTaskEntity after = userTaskRepository.findById(taskId).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getCompletedAt()).isNull();
    }

    @Test
    void completeUserTask_transactionCommitsWhenAuditSucceeds() {
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
        activity2.setType(com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK);
        activity2.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);
        activityRepository.saveAndFlush(activity2);
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId(activity2.getBpmnElementId());
        task.setCreatedAt(Instant.now());
        task.setCandidateGroups("group1");
        userTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        com.zorrodev.bpm.engine.security.Principal principal = new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(UUID.randomUUID(), "test2", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        lenient().when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        when(authorizationService.canCompleteUserTask(any(), any(), any())).thenReturn(true);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        BpmnProcessDefinitionModel bpmnModel2 = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel elementModel2 = mock(BpmnElementModel.class);
        when(bpmnModel2.getElement(any())).thenReturn(elementModel2);
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(bpmnModel2);
        lenient().when(runtimeOperationSupport.toDTO(any())).thenAnswer(inv -> {
            com.zorrodev.bpm.contract.dto.IdDTO dto = new com.zorrodev.bpm.contract.dto.IdDTO();
            dto.setId(taskId);
            return dto;
        });

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());

        com.zorrodev.bpm.contract.dto.IdDTO result = userTaskRuntimeOperations.completeUserTask(taskId, dto);
        assertThat(result.getId()).isEqualTo(taskId);

        UserTaskEntity after = userTaskRepository.findById(taskId).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getCompletedAt()).isNotNull();
    }
}
