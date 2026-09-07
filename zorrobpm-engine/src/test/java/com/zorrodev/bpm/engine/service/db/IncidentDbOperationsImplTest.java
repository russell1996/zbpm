package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentDbOperationsImplTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private DomainEventEmitter domainEventEmitter;
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    @InjectMocks private IncidentDbOperationsImpl db;

    @Test
    void findOpenIncidentsByActivityIds_maps() {
        UUID aId = UUID.randomUUID();
        IncidentEntity e = new IncidentEntity(); e.setId(UUID.randomUUID()); e.setActivityId(aId); e.setMessage("m");
        when(incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(aId))).thenReturn(List.of(e));
        List<Incident> result = db.findOpenIncidentsByActivityIds(List.of(aId));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActivityId()).isEqualTo(aId);
    }

    @Test
    void completeIncidentsByActivityIds_marksCompleted() {
        UUID aId = UUID.randomUUID();
        IncidentEntity e = new IncidentEntity(); e.setId(UUID.randomUUID()); e.setActivityId(aId);
        when(incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(aId))).thenReturn(List.of(e));
        db.completeIncidentsByActivityIds(List.of(aId));
        assertThat(e.getCompletedAt()).isNotNull();
        verify(incidentRepository).saveAll(List.of(e));
    }

    @Test
    void createIncident_savesAndEmitsWithJob() {
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(processInstanceId); activity.setBpmnElementId("Activity_7f3");
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(processInstanceId); pi.setProcessDefinitionId(UUID.randomUUID());
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(pi));
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(activityId); st.setJob("draftCreate");
        when(serviceTaskRepository.findById(activityId)).thenReturn(Optional.of(st));
        UUID id = db.createIncident(activityId, "boom");
        assertThat(id).isNotNull();
        ArgumentCaptor<IncidentEntity> captor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("boom");
        verify(domainEventEmitter).emitIncidentRaised(eq(processInstanceId), any(UUID.class), eq("Activity_7f3"), eq(id), eq("boom"), eq("draftCreate"));
    }

    @Test
    void getIncident_mapsEntity() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity entity = new IncidentEntity(); entity.setId(incidentId); entity.setActivityId(UUID.randomUUID()); entity.setMessage("boom");
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(entity));
        Incident result = db.getIncident(incidentId);
        assertThat(result.getId()).isEqualTo(incidentId);
        assertThat(result.getMessage()).isEqualTo("boom");
    }

    @Test
    void completeIncident_setsCompletedAtAndEmitsWithJob() {
        UUID incidentId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        IncidentEntity entity = new IncidentEntity(); entity.setId(incidentId); entity.setActivityId(activityId);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(entity));
        ActivityEntity activity = new ActivityEntity(); activity.setId(activityId); activity.setProcessInstanceId(processInstanceId); activity.setBpmnElementId("Activity_7f3");
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(processInstanceId); pi.setProcessDefinitionId(UUID.randomUUID());
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(pi));
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(activityId); st.setJob("draftCreate");
        when(serviceTaskRepository.findById(activityId)).thenReturn(Optional.of(st));
        db.completeIncident(incidentId);
        assertThat(entity.getCompletedAt()).isNotNull();
        verify(incidentRepository).save(entity);
        verify(domainEventEmitter).emitIncidentResolved(eq(processInstanceId), any(UUID.class), eq("Activity_7f3"), eq(incidentId), eq("draftCreate"));
    }
}
