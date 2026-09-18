package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Resolved event definition attached to an event element. {@code reference} is the raw
 * {@code errorRef}/{@code signalRef}/{@code escalationRef} as written in the XML; {@code code}/
 * {@code name} are resolved from the definitions-level {@code <error>}/{@code <signal>}/
 * {@code <escalation>} declarations (see {@code BpmnParseService}). {@code expression} carries a
 * conditional event's FEEL condition or a link event's name.
 */
@Getter
@Setter
public class EventDefinitionExtensionModel {
    private EventDefinitionType type;
    private String reference;
    private String code;
    private String name;
    private String expression;
    /**
     * WO-C8-29: resolved {@code zeebe:conditionalFilter} (conditional events only).
     * Null = no filter declared — re-evaluation behavior byte-identical to before.
     */
    private ConditionalFilter conditionalFilter;
}
