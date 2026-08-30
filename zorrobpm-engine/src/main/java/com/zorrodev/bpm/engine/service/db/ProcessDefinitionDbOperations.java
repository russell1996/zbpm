package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessDefinition;

/**
 * WO-DEBT-1b: домен ProcessDefinitions.
 */
public interface ProcessDefinitionDbOperations {

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Integer getMaxProcessDefinitionVersionByKey(String key);
}
