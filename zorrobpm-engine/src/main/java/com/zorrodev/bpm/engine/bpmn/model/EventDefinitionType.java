package com.zorrodev.bpm.engine.bpmn.model;

/**
 * The kind of BPMN event definition attached to an event element (start/end/boundary/intermediate).
 * {@code MESSAGE} and {@code TIMER} keep their dedicated extensions for backward compatibility;
 * this enum covers the remaining definition kinds parsed by {@code BpmnParseService}.
 */
public enum EventDefinitionType {
    ERROR,
    SIGNAL,
    ESCALATION,
    CONDITIONAL,
    LINK,
    COMPENSATE
}
