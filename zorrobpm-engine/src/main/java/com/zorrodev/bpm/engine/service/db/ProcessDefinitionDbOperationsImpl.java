package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * WO-DEBT-1c: домен ProcessDefinitions — реализация.
 * Перенесено 1:1 из DBServiceImpl (getProcessDefinition, getMaxProcessDefinitionVersionByKey).
 */
@Service
@RequiredArgsConstructor
public class ProcessDefinitionDbOperationsImpl implements ProcessDefinitionDbOperations {

    private final ProcessDefinitionRepository processDefinitionRepository;

    @Override
    public ProcessDefinition getProcessDefinition(String key, Integer version) {
        ProcessDefinitionEntity entity = processDefinitionRepository.findByKeyAndVersion(key, version).orElseThrow();
        ProcessDefinition result = new ProcessDefinition();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setKey(entity.getKey());
        result.setSha256(entity.getSha256());
        result.setCreatedAt(entity.getCreatedAt());
        result.setStartFormKey(entity.getStartFormKey());
        result.setVersion(entity.getVersion());
        return result;
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKey(String key) {
        return processDefinitionRepository.findMaxByKey(key).orElse(0);
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKeyAndVersionTag(String key, String versionTag) {
        return processDefinitionRepository.findMaxByKeyAndVersionTag(key, versionTag).orElse(0);
    }
}
