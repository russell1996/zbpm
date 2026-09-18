package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;

/**
 * Handler for a single BPMN element type.
 * <p>
 * Each handler receives an {@link ExecutionCtx} carrying the current execution state
 * (process instance, token, executor callback, depth/guard). The executor is passed as
 * a parameter — not injected — to avoid Spring circular dependencies.
 */
@FunctionalInterface
public interface ElementHandler {

    /**
     * Execute the element logic.
     *
     * @param ctx     execution context (process instance, token, executor, depth guard)
     * @param bpmn    the parsed process definition model
     * @param element the BPMN element being executed
     */
    void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel element);
}
