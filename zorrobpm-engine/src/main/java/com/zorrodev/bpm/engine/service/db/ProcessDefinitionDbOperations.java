package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessDefinition;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен ProcessDefinitions.
 */
public interface ProcessDefinitionDbOperations {

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Integer getMaxProcessDefinitionVersionByKey(String key);

    Integer getMaxProcessDefinitionVersionByKeyAndVersionTag(String key, String versionTag);

    /** WO-C8-3b: latest version of {@code key} laid down in the given deployment (null if none). */
    Integer getMaxProcessDefinitionVersionByKeyAndDeploymentId(String key, UUID deploymentId);

    /** WO-C8-3b: deployment id of a process definition version (null = laid down singly). */
    UUID getDeploymentIdByProcessDefinitionId(UUID processDefinitionId);
}
