package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/** Execution metadata for a boundary event: the id of the activity it is attached to, and whether
 *  it interrupts that activity when it fires (interrupting) or runs in parallel (non-interrupting). */
@Getter
@Setter
public class BoundaryEventExtensionModel {
    private String attachedToRef;
    /** {@code true} (default) cancels the host activity on fire; {@code false} leaves it running and
     *  spawns a parallel branch from the boundary. */
    private boolean interrupting = true;
    /** For a compensation boundary: the id of its compensation handler activity (resolved from the
     *  {@code <bpmn:association>} linking the boundary to the handler). */
    private String compensationHandlerId;
}
