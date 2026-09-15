package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserTaskRuntimeOperationsImplTest {

    @Mock private UserTaskRepository userTaskRepository;
    @Mock private RuntimeService runtimeService;
    @Mock private com.zorrodev.bpm.engine.service.ActivityService activityService;
    @Mock private AuditLogService auditLogService;
    @Mock private FormArtifactService formArtifactService;
    // WO-API-1 (F16): complete читает UserTaskFormData + versionTag + deploymentId.
    @Mock private com.zorrodev.bpm.engine.service.TaskFormDataService taskFormDataService;
    @Mock private com.zorrodev.bpm.engine.service.BpmnService bpmnService;
    @Mock private DBService dbService;
    @Mock private com.zorrodev.bpm.engine.security.AuthorizationService authorizationService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @InjectMocks private UserTaskRuntimeOperationsImpl impl;

    @Test
    void completeUserTask_happyPath() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        task.setFormKey(null);
        // WO-API-1 (F16): complete читает эффективную схему через TaskFormDataService.
        lenient().when(taskFormDataService.loadUserTaskFormData(id)).thenReturn(
            new com.zorrodev.bpm.engine.service.TaskFormDataService.UserTaskFormData(
                id, null, null, null, task.getProcessInstanceId(), "elem", UUID.randomUUID()));
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(true);
        doNothing().when(runtimeOperationSupport).checkAssignee(eq(principal), any(), any(), any());
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        com.zorrodev.bpm.engine.dto.IdDTO engineId = new com.zorrodev.bpm.engine.dto.IdDTO();
        engineId.setId(id);
        when(runtimeService.completeUserTask(id, dto.getVariables())).thenReturn(engineId);
        IdDTO expected = new IdDTO();
        expected.setId(id);
        when(runtimeOperationSupport.toDTO(engineId)).thenReturn(expected);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId())).thenReturn("key");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        IdDTO result = impl.completeUserTask(id, dto);

        assertThat(result).isEqualTo(expected);
        verify(runtimeService).completeUserTask(id, dto.getVariables());
        verify(auditLogService).record(principal, "COMPLETE_USER_TASK", "key", id.toString(), null);
    }

    @Test
    void completeUserTask_completionInProgress_409() {
        // WO-C8-24, критерий 4: повторный complete в completing-фазе маппится в 409
        // (dedicated тип — другие сбои в конфликт не превращаются).
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(true);
        doNothing().when(runtimeOperationSupport).checkAssignee(eq(principal), any(), any(), any());
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        when(runtimeService.completeUserTask(id, dto.getVariables()))
            .thenThrow(new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task 'review' is already completing"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.completeUserTask(id, dto));

        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void completeUserTask_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(false);

        assertThatThrownBy(() -> impl.completeUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void claimUserTask_happyPath() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        task.setAssignee(null);
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(true);
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        when(runtimeOperationSupport.resolvePrincipalId(principal)).thenReturn("alice");
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId())).thenReturn("key");

        IdDTO result = impl.claimUserTask(id);

        assertThat(result.getId()).isEqualTo(id);
        verify(activityService).claimUserTask(id, "alice");
        verify(auditLogService).record(principal, "CLAIM_USER_TASK", "key", id.toString(), "alice");
    }

    @Test
    void claimUserTask_denyWhenAlreadyAssigned() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setAssignee("bob");
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    void claimUserTask_raceCondition_returns409() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        task.setAssignee(null);
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), any(), any())).thenReturn(true);
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        when(runtimeOperationSupport.resolvePrincipalId(any())).thenReturn("alice");
        doThrow(new IllegalStateException("already claimed")).when(activityService).claimUserTask(id, "alice");

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    void claimUserTask_listenerPhaseInProgress_returns409WithPhaseMessage() {
        // WO-C8-28: claim в середине listener-фазы — 409 с именем фазы (тот же
        // dedicated тип, что completing-409; generic IllegalStateException-ветка ниже
        // его не маскирует — порядок catch в прод-коде).
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("group1");
        task.setAssignee(null);
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), any(), any())).thenReturn(true);
        when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        when(runtimeOperationSupport.resolvePrincipalId(any())).thenReturn("alice");
        doThrow(new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
            "User task 'review' is already assigning"))
            .when(activityService).claimUserTask(id, "alice");

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
            .hasMessageContaining("already assigning");
    }

    @Test
    void unclaimUserTask_happyPath() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(true);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId())).thenReturn("key");

        IdDTO result = impl.unclaimUserTask(id);

        assertThat(result.getId()).isEqualTo(id);
        verify(dbService).unclaimUserTask(id);
    }

    @Test
    void assignUserTask_happyPath() {
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("bob");
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())).thenReturn(true);
        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId())).thenReturn("key");

        IdDTO result = impl.assignUserTask(id, dto);

        assertThat(result.getId()).isEqualTo(id);
        verify(activityService).assignUserTask(id, "bob");
    }

    @Test
    void assignUserTask_denyWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("bob");
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())).thenReturn(false);

        assertThatThrownBy(() -> impl.assignUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void assignUserTask_listenerPhaseInProgress_returns409WithPhaseMessage() {
        // WO-C8-28: assign в середине listener-фазы — 409 с именем фазы (зеркало
        // completing-409 из C8-24).
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("bob");
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())).thenReturn(true);
        doThrow(new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
            "User task 'review' is already completing"))
            .when(activityService).assignUserTask(id, "bob");

        assertThatThrownBy(() -> impl.assignUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
            .hasMessageContaining("already completing");
    }

    @Test
    void completeUserTask_unauthorizedWhenNoPrincipal() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        when(runtimeOperationSupport.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> impl.completeUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void completeUserTask_notFound() {
        UUID id = UUID.randomUUID();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.completeUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void claimUserTask_unauthorizedWhenNoPrincipal() {
        UUID id = UUID.randomUUID();
        when(runtimeOperationSupport.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void claimUserTask_notFound() {
        UUID id = UUID.randomUUID();
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void claimUserTask_forbiddenWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCandidateGroups("g1");
        task.setAssignee(null);
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(false);

        assertThatThrownBy(() -> impl.claimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void unclaimUserTask_unauthorizedWhenNoPrincipal() {
        UUID id = UUID.randomUUID();
        when(runtimeOperationSupport.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> impl.unclaimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unclaimUserTask_notFound() {
        UUID id = UUID.randomUUID();
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.unclaimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void unclaimUserTask_forbiddenWhenNotAuthorized() {
        UUID id = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        task.setCompletedAt(null);
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())).thenReturn(false);

        assertThatThrownBy(() -> impl.unclaimUserTask(id))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void assignUserTask_unauthorizedWhenNoPrincipal() {
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("bob");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> impl.assignUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void assignUserTask_notFound() {
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("bob");
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.assignUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void assignUserTask_badRequestWhenAssigneeBlank() {
        UUID id = UUID.randomUUID();
        AssignUserTaskDTO dto = new AssignUserTaskDTO();
        dto.setAssignee("  ");
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);

        assertThatThrownBy(() -> impl.assignUserTask(id, dto))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }
}
