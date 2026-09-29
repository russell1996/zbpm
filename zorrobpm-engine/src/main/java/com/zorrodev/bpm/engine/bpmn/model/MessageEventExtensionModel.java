package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageEventExtensionModel {
    /** Resolved message name (from the referenced definitions-level {@code <bpmn:message>}). */
    private String messageName;
    /** FEEL expression for the correlation key (from the message's {@code zeebe:subscription}), or null.
     *  Evaluated against a subscribing instance's variables to produce the value a message is matched on. */
    private String correlationKeyExpression;
}
