package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
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
class ProcessInstanceDbOperationsImplTest {

    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private VariableRepository variableRepository;
    // WO-ENG-16: стартовые переменные входят в историю (мок — поведение покрыто IT).
    @Mock private VariableHistoryWriter historyWriter;
    @Mock private DomainEventEmitter domainEventEmitter;
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    @InjectMocks private ProcessInstanceDbOperationsImpl db;

    @Test
    void createProcessInstance_savesEntityAndVariablesAndEmits() {
        UUID parent = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessVariable pv = new ProcessVariable(); pv.setName("k"); pv.setValue("v"); pv.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
        UUID id = db.createProcessInstance(parent, pdId, List.of(pv));
        assertThat(id).isNotNull();
        ArgumentCaptor<ProcessInstanceEntity> captor = ArgumentCaptor.forClass(ProcessInstanceEntity.class);
        verify(processInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().getParentActivityId()).isEqualTo(parent);
        assertThat(captor.getValue().getProcessDefinitionId()).isEqualTo(pdId);
        verify(variableRepository).saveAll(any(List.class));
        // WO-ENG-16: стартовые переменные — первая строка истории (источник INIT).
        verify(historyWriter).record(eq(id), eq(null), any(ProcessVariable.class), eq("INIT"));
        verify(domainEventEmitter).emitProcessInstanceStarted(eq(id), eq(pdId));
    }

    @Test
    void getProcessInstance_maps() {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id);
        ProcessInstance dto = new ProcessInstance(); dto.setId(id);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        when(processInstanceMapper.toDTO(e)).thenReturn(dto);
        assertThat(db.getProcessInstance(id)).isEqualTo(dto);
    }

    @Test
    void lockProcessInstance_locks() {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id);
        when(processInstanceRepository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
        db.lockProcessInstance(id);
        verify(processInstanceRepository).findByIdForUpdate(id);
    }

    @Test
    void completeProcessInstance_setsCompletedAndEmits() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.completeProcessInstance(id);
        verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        verify(domainEventEmitter).emitProcessInstanceCompleted(eq(id), eq(pdId));
    }

    @Test
    void cancelProcessInstance_setsCancelledAndCompletedAndEmits() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.cancelProcessInstance(id);
        verify(processInstanceRepository).setCancelled(eq(id), eq(true));
        verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        verify(domainEventEmitter).emitProcessInstanceCancelled(eq(id), eq(pdId));
    }
}
