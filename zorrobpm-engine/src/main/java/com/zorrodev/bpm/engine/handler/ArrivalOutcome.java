package com.zorrodev.bpm.engine.handler;

/**
 * WO-QW-1 Q-2: outcome of {@link FlowNavigator#handleAdHocArrival} — replaces the
 * bare {@code boolean} (boolean-blindness: at the call site {@code if (handleAdHocArrival(...))}
 * never said what {@code true} meant).
 */
public enum ArrivalOutcome {
    /** A scope finished here and consumed this branch — caller must not flow further. */
    SCOPE_FINISHED,
    /** Nothing consumed — caller continues with the element's own outgoing flows. */
    CONTINUE
}
