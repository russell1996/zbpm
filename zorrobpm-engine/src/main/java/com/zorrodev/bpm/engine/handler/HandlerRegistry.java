package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private volatile Map<BpmnElementType, ElementHandler> handlers;
    private volatile boolean beanHandlersResolved = false;

    public HandlerRegistry(ObjectProvider<TypedElementHandler> typedHandlers) {
        this.typedHandlers = typedHandlers;
        this.handlers = new ConcurrentHashMap<>();
    }

    /**
     * WO-AUDIT-5: publish-then-flag. The resolved map is built LOCALLY and
     * assigned whole; only afterwards is {@code beanHandlersResolved} set.
     * A concurrent reader that observes {@code true} (volatile) is therefore
     * guaranteed (happens-before) to observe the fully populated map — never a
     * half-filled one. Previously the flag was set BEFORE the fill loop.
     */
    private synchronized void resolveBeanHandlers() {
        if (beanHandlersResolved) {
            return;
        }
        // Local build stays EnumMap (single-threaded fill); the published field is
        // always a ConcurrentHashMap copy — publication and register() share one type.
        Map<BpmnElementType, ElementHandler> resolved = new EnumMap<>(BpmnElementType.class);
        for (TypedElementHandler handler : typedHandlers.orderedStream().toList()) {
            resolved.put(handler.elementType(), handler.handler());
            log.info("Registered handler for {}: {}", handler.elementType(), handler.handler().getClass().getSimpleName());
        }
        registerAliases(resolved);
        handlers = new ConcurrentHashMap<>(resolved);
        beanHandlersResolved = true;
    }

    /**
     * WO-A-08: Register element type aliases after bean resolution.
     */
    private void registerAliases(Map<BpmnElementType, ElementHandler> map) {
        putIfPresent(map, BpmnElementType.MESSAGE_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(map, BpmnElementType.TIMER_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(map, BpmnElementType.SIGNAL_START_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(map, BpmnElementType.LINK_CATCH_EVENT, BpmnElementType.START_EVENT);
        putIfPresent(map, BpmnElementType.RECEIVE_TASK, BpmnElementType.MESSAGE_CATCH_EVENT);
    }

    private void putIfPresent(Map<BpmnElementType, ElementHandler> map, BpmnElementType alias, BpmnElementType target) {
        ElementHandler handler = map.get(target);
        if (handler != null) {
            map.putIfAbsent(alias, handler);
            log.info("Alias {} → {}", alias, target);
        }
    }

    /**
     * Register a handler for a given element type.
     *
     * WO-QW-1 C-2: ConcurrentHashMap — correct by construction under concurrent
     * register/get (the old EnumMap.put raced a concurrent get during lazy
     * resolution; only startup/tests hit it today, but the class must not rely
     * on that staying true).
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
