package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1f: домен ProcessInstances — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов).
 */
@Service
@RequiredArgsConstructor
public class ProcessInstanceDbOperationsImpl implements ProcessInstanceDbOperations {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final BpmMetrics bpmMetrics;
    private final VariableRepository variableRepository;
    /** WO-ENG-16: стартовые переменные тоже входят в историю (источник INIT). */
    private final VariableHistoryWriter historyWriter;
    private final DomainEventEmitter domainEventEmitter;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        return createProcessInstance(parentActivityId, processDefinitionId, variables, null);
    }

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
            List<ProcessVariable> variables, String claimedInitiator) {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setStartedAt(Instant.now());
        entity.setParentActivityId(parentActivityId);
        // WO-API-1 (API-7): initiator в том же INSERT — атомарно со стартом.
        entity.setInitiator(claimedInitiator);
        processInstanceRepository.save(entity);
        List<ProcessVariableEntity> vs = new LinkedList<>();
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            ProcessVariableEntity v = new ProcessVariableEntity();
            v.setId(UUID.randomUUID());
            v.setProcessInstanceId(id);
            v.setName(variable.getName());
            v.setType(variable.getType());
            v.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            vs.add(v);
        }
        variableRepository.saveAll(vs);
        // WO-ENG-16: начальные значения — первая строка истории каждой
        // переменной (без этого история начиналась бы с первого изменения,
        // а исходное значение терялось бы — ровно тот пробел из WB-003).
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            historyWriter.record(id, null, variable, VariableHistoryWriter.SOURCE_INIT);
        }
        domainEventEmitter.emitProcessInstanceStarted(id, processDefinitionId);
        return id;
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity entity = processInstanceRepository.findById(processInstanceId).orElseThrow();
        return processInstanceMapper.toDTO(entity);
    }

    @Override
    public void lockProcessInstance(UUID processInstanceId) {
        processInstanceRepository.findByIdForUpdate(processInstanceId).orElseThrow();
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        domainEventEmitter.emitProcessInstanceCompleted(processInstanceId, pi.getProcessDefinitionId());
        // WO-OBS-1: single choke for every completion path (normal end, escalation end).
        bpmMetrics.processCompleted();
        bpmMetrics.decrementActiveInstances();
    }

    @Override
    public void cancelProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        processInstanceRepository.setCancelled(processInstanceId, true);
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        domainEventEmitter.emitProcessInstanceCancelled(processInstanceId, pi.getProcessDefinitionId());
        // WO-OBS-1: cancel ends the instance too (gauge must not leak); not a failure.
        bpmMetrics.decrementActiveInstances();
    }
}
