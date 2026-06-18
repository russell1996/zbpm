package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageEventExtensionModel {
    /** Resolved message name (from the referenced definitions-level {@code <bpmn:message>}). */
    private String messageName;
}
