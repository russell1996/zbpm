package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/** Execution metadata for a boundary event: the id of the activity it is attached to. */
@Getter
@Setter
public class BoundaryEventExtensionModel {
    private String attachedToRef;
}
