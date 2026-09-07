package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1g: домен Variables — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов + toProcessVariable).
 */
@Service
@RequiredArgsConstructor
public class VariableDbOperationsImpl implements VariableDbOperations {

    private final VariableRepository variableRepository;
    private final ExecutionContext executionContext;

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .map(this::toProcessVariable)
            .toList();
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        Map<String, ProcessVariable> merged = new LinkedHashMap<>();
        for (ProcessVariableEntity e : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        for (ProcessVariableEntity e : variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, scopeId)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        return new ArrayList<>(merged.values());
    }

    private ProcessVariable toProcessVariable(ProcessVariableEntity variable) {
        ProcessVariable result = new ProcessVariable();
        result.setName(variable.getName());
        result.setType(variable.getType());
        result.setValue(variable.getTextValue());
        return result;
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        setVariables(processInstanceId, null, variables);
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables) {
        List<ProcessVariableEntity> entities = new ArrayList<>();
        for (ProcessVariable variable : variables) {
            Optional<ProcessVariableEntity> existing = (scopeId == null
                ? variableRepository.findByNameAndProcessInstanceIdAndScopeIdIsNull(variable.getName(), processInstanceId)
                : variableRepository.findByNameAndProcessInstanceIdAndScopeId(variable.getName(), processInstanceId, scopeId));
            ProcessVariableEntity entity = existing.orElseGet(() -> {
                    ProcessVariableEntity ne = new ProcessVariableEntity();
                    ne.setId(UUID.randomUUID());
                    ne.setProcessInstanceId(processInstanceId);
                    ne.setScopeId(scopeId);
                    ne.setName(variable.getName());
                    return ne;
                });
            entity.setType(variable.getType());
            entity.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            entities.add(entity);
            // WO-C8-29: record for conditionalFilter matching (create vs update is known
            // exactly here — the row either existed or not).
            executionContext.recordVariableChange(variable.getName(), existing.isPresent() ? "update" : "create");
        }
        variableRepository.saveAll(entities);
    }

    @Override
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableRepository.deleteByProcessInstanceIdAndScopeId(processInstanceId, scopeId);
    }
}
