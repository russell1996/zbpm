package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/** Execution metadata for an embedded subprocess: the id of its (nested) start event. */
@Getter
@Setter
public class SubProcessExtensionModel {
    private String startEventId;
    /** True when this is an event sub-process (triggered by an event, not an incoming flow). */
    private boolean eventSubProcess;
    /** For an event sub-process: whether its start event interrupts the parent scope (default true). */
    private boolean interrupting;
    /** For a message-triggered event sub-process: the resolved name of the triggering message. */
    private String triggerMessageName;
}
