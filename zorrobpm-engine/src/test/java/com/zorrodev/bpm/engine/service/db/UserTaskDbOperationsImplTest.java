package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserTaskDbOperationsImplTest {

    @Mock private UserTaskRepository userTaskRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private DomainEventEmitter domainEventEmitter;
    @InjectMocks private UserTaskDbOperationsImpl db;

    @Test
    void createUserTask_savesEntityAndEmits() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("ut1"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        db.createUserTask(activityId, "ivanov", "managers", "form1", null, null, null, null, null);
        ArgumentCaptor<UserTaskEntity> captor = ArgumentCaptor.forClass(UserTaskEntity.class);
        verify(userTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getAssignee()).isEqualTo("ivanov");
        verify(domainEventEmitter).emitUserTaskCreated(eq(activity.getProcessInstanceId()), any(UUID.class), eq("ut1"), eq(activityId), eq("ivanov"), eq("managers"));
    }

    @Test
    void createUserTask_persistsDueAndFollowUpDates() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("ut1"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        db.createUserTask(activityId, "ivanov", "managers", "form1", null, null, "2030-01-01", "2030-01-05", 75);
        ArgumentCaptor<UserTaskEntity> captor = ArgumentCaptor.forClass(UserTaskEntity.class);
        verify(userTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isEqualTo("2030-01-01");
        // WO-C8-30: priority rides the row.
        assertThat(captor.getValue().getPriority()).isEqualTo(75);
        assertThat(captor.getValue().getFollowUpDate()).isEqualTo("2030-01-05");
    }

    @Test
    void completeUserTask_callsRepositoryAndEmits() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(UUID.randomUUID()); ut.setProcessDefinitionId(UUID.randomUUID()); ut.setBpmnElementId("ut1"); ut.setAssignee("petrov"); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        db.completeUserTask(id);
        verify(userTaskRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        verify(domainEventEmitter).emitUserTaskCompleted(eq(ut.getProcessInstanceId()), any(UUID.class), eq("ut1"), eq(id), eq("petrov"));
    }

    @Test
    void claimUserTask_claimsWhenUnassigned() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee(null); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        when(userTaskRepository.claimAssignee(id, "ivanov")).thenReturn(1);
        db.claimUserTask(id, "ivanov");
        verify(domainEventEmitter).emitUserTaskAssigned(pi, pd, "ut", id, "ivanov");
    }

    @Test
    void claimUserTask_throwsWhenAlreadyCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(java.time.Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> db.claimUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class).hasMessageContaining("already completed");
    }

    @Test
    void claimUserTask_throwsWhenAlreadyAssigned() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(null); ut.setProcessInstanceId(UUID.randomUUID()); ut.setProcessDefinitionId(UUID.randomUUID()); ut.setBpmnElementId("ut");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        when(userTaskRepository.claimAssignee(id, "ivanov")).thenReturn(0);
        assertThatThrownBy(() -> db.claimUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class).hasMessageContaining("already assigned");
    }

    @Test
    void unclaimUserTask_clearsAssignee() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee("x"); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        db.unclaimUserTask(id);
        verify(userTaskRepository).setAssignee(id, null);
        verify(domainEventEmitter).emitUserTaskUnassigned(pi, pd, "ut", id);
    }

    @Test
    void unclaimUserTask_throwsWhenCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(java.time.Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> db.unclaimUserTask(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignUserTask_setsAssignee() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee(null); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        db.assignUserTask(id, "ivanov");
        verify(userTaskRepository).setAssignee(id, "ivanov");
        verify(domainEventEmitter).emitUserTaskAssigned(pi, pd, "ut", id, "ivanov");
    }

    @Test
    void assignUserTask_throwsWhenCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(java.time.Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> db.assignUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class);
    }
}
