package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class UserTaskClaimTransactionIT {

    @Autowired private UserTaskRuntimeOperations userTaskRuntimeOperations;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;
    @MockitoBean private AuthorizationService authorizationService;
    // WO-C8-28: claim идёт через phase-aware ActivityService (assigning-фаза);
    // механика фаз покрыта engine- и unit-тестами, здесь важен только rollback
    // вокруг аудита — мокаем коллаборатор, как остальные внешние зависимости.
    @MockitoBean private com.zorrodev.bpm.engine.service.ActivityService activityService;
    // Реальный DBService — мок выше делегирует ему запись claim (старое прямое
    // поведение), чтобы assertions про assignee остались проверяющими запись.
    @Autowired private com.zorrodev.bpm.engine.service.DBService dbService;

    private UUID cleanupTask, cleanupPi, cleanupPd;

    @AfterEach
    void cleanup() {
        if (cleanupTask != null) userTaskRepository.deleteById(cleanupTask);
        if (cleanupPi != null) processInstanceRepository.deleteById(cleanupPi);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupTask = cleanupPi = cleanupPd = null;
    }

    @Test
    void claimUserTask_transactionRollsBackWhenAuditFails() {
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
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId("task-" + taskId.toString().substring(0, 8));
        task.setCreatedAt(Instant.now());
        task.setCandidateGroups("group1");
        task.setAssignee(null);
        userTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        // Mock the other methods to allow
        lenient().when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        lenient().when(runtimeOperationSupport.resolvePrincipalId(any())).thenReturn("test");
        doNothing().when(runtimeOperationSupport).checkAssignee(any(), any(), any(), any());
        doNothing().when(runtimeOperationSupport).requireOnBehalfMatchesTask(any(), any(), any(), any());
        when(authorizationService.canClaimUserTask(any(), any(), any())).thenReturn(true);

        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), eq("CLAIM_USER_TASK"), any(), eq(taskId.toString()), any());
        // WO-C8-28: эмуляция старого прямого вызова (запись claim + откат вместе с аудитом).
        doAnswer(inv -> {
            dbService.claimUserTask(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(activityService).claimUserTask(any(), any());

        assertThatThrownBy(() -> userTaskRuntimeOperations.claimUserTask(taskId))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("audit fail");

        UserTaskEntity after = userTaskRepository.findById(taskId).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getAssignee()).isNull();
    }

    @Test
    void claimUserTask_transactionCommitsWhenAuditSucceeds() {
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
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setProcessDefinitionId(pdId);
        task.setBpmnElementId("task-" + taskId.toString().substring(0, 8));
        task.setCreatedAt(Instant.now());
        task.setCandidateGroups("group1");
        userTaskRepository.saveAndFlush(task);
        cleanupTask = taskId;

        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "test2", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId)).thenReturn("k-" + pdId.toString().substring(0, 8));
        doNothing().when(runtimeOperationSupport).requireOperate(any(), any());
        lenient().when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        lenient().when(runtimeOperationSupport.resolvePrincipalId(any())).thenReturn("test2");
        when(authorizationService.canClaimUserTask(any(), any(), any())).thenReturn(true);
        // WO-C8-28: эмуляция старого прямого вызова (реальная запись claim).
        doAnswer(inv -> {
            dbService.claimUserTask(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(activityService).claimUserTask(any(), any());

        // Should not throw
        // This will try to claim the task, which should succeed
        com.zorrodev.bpm.contract.dto.IdDTO result = userTaskRuntimeOperations.claimUserTask(taskId);
        assertThat(result.getId()).isEqualTo(taskId);

        UserTaskEntity after = userTaskRepository.findById(taskId).orElse(null);
        assertThat(after).isNotNull();
        assertThat(after.getAssignee()).isEqualTo("test2");
    }
}
