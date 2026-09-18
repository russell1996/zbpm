package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTaskDbOperationsImplTest {

    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private DomainEventEmitter domainEventEmitter;
    @InjectMocks private ServiceTaskDbOperationsImpl db;

    @Test
    void createServiceTask_singleArg_delegatesToThreeArg() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("svc"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        db.createServiceTask(activityId);
        verify(serviceTaskRepository).save(any(ServiceTaskEntity.class));
        verify(domainEventEmitter).emitServiceTaskCreated(eq(activity.getProcessInstanceId()), any(UUID.class), eq("svc"), eq(activityId), eq(null));
    }

    @Test
    void createServiceTask_withJob_savesEntityAndEmits() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("Activity_7f3"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        db.createServiceTask(activityId, 3, "draftCreate");
        ArgumentCaptor<ServiceTaskEntity> captor = ArgumentCaptor.forClass(ServiceTaskEntity.class);
        verify(serviceTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getJob()).isEqualTo("draftCreate");
        assertThat(captor.getValue().getRetriesRemaining()).isEqualTo(3);
        verify(domainEventEmitter).emitServiceTaskCreated(eq(activity.getProcessInstanceId()), any(UUID.class), eq("Activity_7f3"), eq(activityId), eq("draftCreate"));
    }

    @Test
    void decrementServiceTaskRetries_returnsDecremented() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setRetriesRemaining(3);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        int remaining = db.decrementServiceTaskRetries(id);
        assertThat(remaining).isEqualTo(2);
        verify(serviceTaskRepository).save(e);
        assertThat(e.getRetriesRemaining()).isEqualTo(2);
    }

    @Test
    void decrementServiceTaskRetries_nullHandledAsOne() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setRetriesRemaining(null);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        int remaining = db.decrementServiceTaskRetries(id);
        assertThat(remaining).isEqualTo(0);
        assertThat(e.getRetriesRemaining()).isEqualTo(0);
    }

    @Test
    void setServiceTaskRetries_saves() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setRetriesRemaining(1);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        db.setServiceTaskRetries(id, 5);
        assertThat(e.getRetriesRemaining()).isEqualTo(5);
        verify(serviceTaskRepository).save(e);
    }

    @Test
    void completeServiceTask_callsRepository() {
        UUID id = UUID.randomUUID();
        db.completeServiceTask(id);
        verify(serviceTaskRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
    }

    @Test
    void createServiceTask_withListenerIndex_savesIt() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("svc"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        // WO-C8-11: entering an element with start listeners parks listener #0 first.
        db.createServiceTask(activityId, 3, "job-c8", 0);
        ArgumentCaptor<ServiceTaskEntity> captor = ArgumentCaptor.forClass(ServiceTaskEntity.class);
        verify(serviceTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getPendingListenerIndex()).isEqualTo(0);
    }

    @Test
    void createServiceTask_threeArg_leavesListenerIndexNull() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(UUID.randomUUID()); activity.setBpmnElementId("svc"); activity.setCreatedAt(java.time.Instant.now());
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(activity.getProcessInstanceId()); pi.setProcessDefinitionId(UUID.randomUUID());
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(activity.getProcessInstanceId())).thenReturn(Optional.of(pi));
        // WO-C8-11, критерий 5: pre-existing overload delegates with a null index (normal path).
        db.createServiceTask(activityId, 3, "job-c8");
        ArgumentCaptor<ServiceTaskEntity> captor = ArgumentCaptor.forClass(ServiceTaskEntity.class);
        verify(serviceTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getPendingListenerIndex()).isNull();
    }

    @Test
    void setAndGetPendingListenerIndex_roundTrip() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setPendingListenerIndex(null);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        db.setPendingListenerIndex(id, 1);
        assertThat(e.getPendingListenerIndex()).isEqualTo(1);
        verify(serviceTaskRepository).save(e);
        assertThat(db.getPendingListenerIndex(id)).isEqualTo(1);
        db.setPendingListenerIndex(id, null);
        assertThat(e.getPendingListenerIndex()).isNull();
        assertThat(db.getPendingListenerIndex(id)).isNull();
    }

    @Test
    void setAndGetPendingEndListenerIndex_roundTrip() {
        // WO-C8-11b: separate column, same discipline as the start index.
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setPendingEndListenerIndex(null);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        db.setPendingEndListenerIndex(id, 1);
        assertThat(e.getPendingEndListenerIndex()).isEqualTo(1);
        verify(serviceTaskRepository).save(e);
        assertThat(db.getPendingEndListenerIndex(id)).isEqualTo(1);
        db.setPendingEndListenerIndex(id, null);
        assertThat(e.getPendingEndListenerIndex()).isNull();
        assertThat(db.getPendingEndListenerIndex(id)).isNull();
    }
}
