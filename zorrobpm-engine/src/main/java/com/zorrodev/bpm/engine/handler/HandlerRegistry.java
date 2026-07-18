package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry mapping {@link BpmnElementType} → {@link ElementHandler}.
 * <p>
 * Uses {@link ObjectProvider} to lazily discover {@link TypedElementHandler} beans.
 * Beans are resolved on first {@link #get} call — not during construction or register().
 */
@Slf4j
@Component
public class HandlerRegistry {

    private final ObjectProvider<TypedElementHandler> typedHandlers;
    @Getter
    private final Map<BpmnElementType, ElementHandler> handlers;
    private volatile boolean beanHandlersResolved = false;

    public HandlerRegistry(ObjectProvider<TypedElementHandler> typedHandlers) {
        this.typedHandlers = typedHandlers;
        this.handlers = new EnumMap<>(BpmnElementType.class);
    }

    private synchronized void resolveBeanHandlers() {
        if (beanHandlersResolved) {
            return;
        }
        beanHandlersResolved = true;
        for (TypedElementHandler handler : typedHandlers.orderedStream().toList()) {
            handlers.put(handler.elementType(), handler.handler());
            log.info("Registered handler for {}: {}", handler.elementType(), handler.handler().getClass().getSimpleName());
        }
    }

    /**
     * Register a handler for a given element type.
     */
    public void register(BpmnElementType type, ElementHandler handler) {
        handlers.put(type, handler);
    }

    /**
     * Look up the handler for a given element type.
     */
    public ElementHandler get(BpmnElementType type) {
        resolveBeanHandlers();
        return handlers.get(type);
    }
}
