package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityDbOperationsImplTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private DomainEventEmitter domainEventEmitter;
    @InjectMocks private ActivityDbOperationsImpl db;

    @Test
    void createActivity_byElement_persists() {
        UUID pi = UUID.randomUUID(); UUID token = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel(); el.setId("startEvent"); el.setType(BpmnElementType.START_EVENT);
        UUID id = db.createActivity(pi, token, el);
        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getBpmnElementId()).isEqualTo("startEvent");
    }

    @Test
    void createActivity_byFlow_persists() {
        UUID pi = UUID.randomUUID(); UUID token = UUID.randomUUID();
        BpmnFlowModel flow = new BpmnFlowModel(); flow.setFlowId("flow1");
        UUID id = db.createActivity(pi, token, flow);
        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(BpmnElementType.SEQUENCE_FLOW);
    }

    @Test
    void completeActivity_marksCompletedAndEmitsWithJob() {
        UUID activityId = UUID.randomUUID(); UUID piId = UUID.randomUUID();
        ActivityEntity act = new ActivityEntity(); act.setId(activityId); act.setProcessInstanceId(piId); act.setBpmnElementId("Activity_7f3");
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(act));
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(UUID.randomUUID());
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(activityId); st.setJob("draftCreate");
        when(serviceTaskRepository.findById(activityId)).thenReturn(Optional.of(st));
        db.completeActivity(activityId);
        verify(activityRepository).setStatusAndCompletedAt(eq(activityId), eq(ActivityStatus.COMPLETED), any(java.time.Instant.class));
        verify(domainEventEmitter).emitActivityCompleted(eq(piId), any(UUID.class), eq("Activity_7f3"), eq("draftCreate"));
    }

    @Test
    void getActiveActivities_returnsMapped() {
        UUID pi = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity(); e.setId(UUID.randomUUID()); e.setBpmnElementId("x"); e.setType(BpmnElementType.USER_TASK); e.setStatus(ActivityStatus.CREATED); e.setProcessInstanceId(pi); e.setToken(UUID.randomUUID());
        when(activityRepository.findByProcessInstanceIdAndStatusInOrderByIdAsc(eq(pi), any(List.class))).thenReturn(List.of(e));
        List<Activity> result = db.getActiveActivities(pi);
        assertThat(result).hasSize(1);
    }

    @Test
    void hasActiveActivityOnTokenAndElement_trueWhenPresent() {
        UUID token = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity(); e.setId(UUID.randomUUID());
        when(activityRepository.findByTokenAndBpmnElementIdAndStatusIn(eq(token), eq("x"), any(List.class))).thenReturn(List.of(e));
        assertThat(db.hasActiveActivityOnTokenAndElement(token, "x")).isTrue();
    }

    @Test
    void getActivity_returnsMapped() {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity(); entity.setId(id); entity.setBpmnElementId("e1"); entity.setType(BpmnElementType.SERVICE_TASK); entity.setStatus(ActivityStatus.CREATED); entity.setProcessInstanceId(UUID.randomUUID()); entity.setToken(UUID.randomUUID());
        when(activityRepository.findById(id)).thenReturn(Optional.of(entity));
        Activity result = db.getActivity(id);
        assertThat(result.getId()).isEqualTo(id);
    }
}
