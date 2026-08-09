package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProcessInstanceMapper {

    private final ProcessDefinitionRepository processDefinitionRepository;

    public ProcessInstance toDTO(ProcessInstanceEntity entity) {
        return toDTO(entity, findDefinition(entity.getProcessDefinitionId()));
    }

    /**
     * WO-PERF-2 (D-01): batch-loads all referenced process definitions in ONE query instead of
     * one findById per row (a page of 200 instances previously issued up to 200 extra SELECTs).
     */
    public List<ProcessInstance> toDTOs(List<ProcessInstanceEntity> entities) {
        Map<UUID, ProcessDefinitionEntity> definitions = loadDefinitions(entities);
        return entities.stream()
            .map(e -> toDTO(e, definitions.get(e.getProcessDefinitionId())))
            .toList();
    }

    private Map<UUID, ProcessDefinitionEntity> loadDefinitions(List<ProcessInstanceEntity> entities) {
        List<UUID> ids = entities.stream()
            .map(ProcessInstanceEntity::getProcessDefinitionId)
            .distinct()
            .toList();
        return processDefinitionRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ProcessDefinitionEntity::getId, Function.identity()));
    }

    private ProcessDefinitionEntity findDefinition(UUID processDefinitionId) {
        return processDefinitionRepository.findById(processDefinitionId).orElse(null);
    }

    private ProcessInstance toDTO(ProcessInstanceEntity entity, ProcessDefinitionEntity pd) {
        ProcessInstance pi = new ProcessInstance();
        pi.setId(entity.getId());
        pi.setParentActivityId(entity.getParentActivityId());
        pi.setStartedAt(entity.getStartedAt());
        pi.setCompletedAt(entity.getCompletedAt());
        pi.setProcessDefinitionId(entity.getProcessDefinitionId());
        // resolve the definition's name/key/version so lists/detail can show a human label
        if (pd != null) {
            pi.setProcessName(pd.getName());
            pi.setProcessKey(pd.getKey());
            pi.setProcessVersion(pd.getVersion());
        }
        return pi;
    }
}
