package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;

/**
 * Marker interface for handler beans that declare which {@link BpmnElementType} they handle.
 * Used by {@link HandlerRegistry} to auto-discover handler beans via Spring's ObjectProvider.
 */
public interface TypedElementHandler {

    /**
     * The BPMN element type this handler serves.
     */
    BpmnElementType elementType();

    /**
     * Returns the handler implementation (typically {@code this}).
     */
    ElementHandler handler();
}
