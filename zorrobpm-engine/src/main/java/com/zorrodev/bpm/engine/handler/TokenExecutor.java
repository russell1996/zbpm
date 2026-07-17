package com.zorrodev.bpm.engine.handler;

import java.util.UUID;

/**
 * Port interface for executing a BPMN element within a process instance.
 * <p>
 * Implemented by {@code ActivityServiceImpl}. Passed as a parameter to handlers
 * (not injected) to avoid Spring circular dependencies.
 */
@FunctionalInterface
public interface TokenExecutor {

    /**
     * Execute a BPMN element.
     *
     * @param processInstanceId the process instance
     * @param tokenId           the token to advance
     * @param bpmnElementId     the element ID to execute
     */
    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);
}
