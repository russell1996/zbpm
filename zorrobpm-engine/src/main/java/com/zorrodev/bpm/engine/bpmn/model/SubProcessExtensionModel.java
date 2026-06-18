package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/** Execution metadata for an embedded subprocess: the id of its (nested) start event. */
@Getter
@Setter
public class SubProcessExtensionModel {
    private String startEventId;
}
