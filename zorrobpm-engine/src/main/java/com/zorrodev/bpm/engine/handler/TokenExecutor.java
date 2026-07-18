package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;

import java.util.UUID;

/**
 * Port interface for executing a BPMN element within a process instance.
 * <p>
 * Implemented by {@code ActivityServiceImpl}. Passed as a parameter to handlers
 * (not injected) to avoid Spring circular dependencies.
 */
public interface TokenExecutor {

    /**
     * Execute a BPMN element by its string ID (looks up the element from the process definition).
     */
    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    /**
     * Execute a BPMN element that has already been resolved from the process definition.
     */
    void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element);
}
