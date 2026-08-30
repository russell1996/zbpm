package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен ProcessInstances.
 */
public interface ProcessInstanceDbOperations {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    void completeProcessInstance(UUID processInstanceId);

    void cancelProcessInstance(UUID processInstanceId);

    void lockProcessInstance(UUID processInstanceId);
}
