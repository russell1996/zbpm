package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VariableDbOperationsImplTest {

    @Mock private VariableRepository variableRepository;
    // WO-C8-29: запись изменений для conditionalFilter (мок — существующие тесты
    // трекинг не проверяют; поведение трекера покрыто отдельно).
    @Mock private com.zorrodev.bpm.engine.handler.ExecutionContext executionContext;
    @InjectMocks private VariableDbOperationsImpl db;

    @Test
    void getVariables_root_returnsMapped() {
        UUID pi = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(pi); e.setName("k"); e.setType(ProcessVariableType.STRING); e.setTextValue("v");
        when(variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)).thenReturn(List.of(e));
        List<ProcessVariable> result = db.getVariables(pi);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("k");
        assertThat(result.get(0).getValue()).isEqualTo("v");
    }

    @Test
    void getVariables_withScope_mergesRootAndScope() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        ProcessVariableEntity root = new ProcessVariableEntity(); root.setId(UUID.randomUUID()); root.setProcessInstanceId(pi); root.setName("k"); root.setType(ProcessVariableType.STRING); root.setTextValue("root");
        ProcessVariableEntity scoped = new ProcessVariableEntity(); scoped.setId(UUID.randomUUID()); scoped.setProcessInstanceId(pi); scoped.setScopeId(scope); scoped.setName("k"); scoped.setType(ProcessVariableType.STRING); scoped.setTextValue("scoped");
        when(variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)).thenReturn(List.of(root));
        when(variableRepository.findByProcessInstanceIdAndScopeId(pi, scope)).thenReturn(List.of(scoped));
        List<ProcessVariable> result = db.getVariables(pi, scope);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("scoped");
    }

    @Test
    void setVariables_withScope_saves() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        ProcessVariable pv = new ProcessVariable(); pv.setName("k"); pv.setValue("v"); pv.setType(ProcessVariableType.STRING);
        when(variableRepository.findByNameAndProcessInstanceIdAndScopeId("k", pi, scope)).thenReturn(Optional.empty());
        db.setVariables(pi, scope, List.of(pv));
        ArgumentCaptor<List<ProcessVariableEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getName()).isEqualTo("k");
    }

    @Test
    void deleteVariables_deletes() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        db.deleteVariables(pi, scope);
        verify(variableRepository).deleteByProcessInstanceIdAndScopeId(pi, scope);
    }

    @Test
    void setVariables_root_delegatesToScopeNull() {
        UUID pi = UUID.randomUUID();
        ProcessVariable pv = new ProcessVariable(); pv.setName("k"); pv.setValue("v"); pv.setType(ProcessVariableType.STRING);
        when(variableRepository.findByNameAndProcessInstanceIdAndScopeIdIsNull("k", pi)).thenReturn(Optional.empty());
        db.setVariables(pi, List.of(pv));
        verify(variableRepository).saveAll(any(List.class));
    }
}
