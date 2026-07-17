package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry mapping {@link BpmnElementType} → {@link ElementHandler}.
 * <p>
 * Built from handler beans at construction time. Currently {@code ActivityServiceImpl}
 * registers its own inline handlers; later phases will register extracted handler beans.
 */
@Component
public class HandlerRegistry {

    @Getter
    private final Map<BpmnElementType, ElementHandler> handlers;

    public HandlerRegistry() {
        this.handlers = new EnumMap<>(BpmnElementType.class);
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
        return handlers.get(type);
    }
}
