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
 * Aliases (e.g. MESSAGE_START_EVENT → START_EVENT) are registered after bean resolution.
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
        registerAliases();
    }

    /**
     * WO-A-08: Register element type aliases after bean resolution.
     */
    private void registerAliases() {
        putIfPresent(BpmnElementType.MESSAGE_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(BpmnElementType.TIMER_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(BpmnElementType.SIGNAL_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(BpmnElementType.LINK_CATCH_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(BpmnElementType.RECEIVE_TASK, BpmnElementType.MESSAGE_CATCH_EVENT);
    }

    private void putIfPresent(BpmnElementType alias, BpmnElementType target) {
        ElementHandler handler = handlers.get(target);
        if (handler != null) {
            handlers.putIfAbsent(alias, handler);
            log.info("Alias {} → {}", alias, target);
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
