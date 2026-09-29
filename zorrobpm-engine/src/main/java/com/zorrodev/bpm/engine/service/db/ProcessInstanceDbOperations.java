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

    /**
     * WO-API-1 (API-7): тот же create, но initiator пишется в том же INSERT —
     * вместо второго save после старта. Null = без инициатора (старый путь).
     */
    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
        List<ProcessVariable> variables, String claimedInitiator);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    void completeProcessInstance(UUID processInstanceId);

    void cancelProcessInstance(UUID processInstanceId);

    void lockProcessInstance(UUID processInstanceId);
}
