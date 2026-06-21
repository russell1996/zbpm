package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Execution model for a multi-instance activity: whether it is sequential or parallel and the (literal or
 * FEEL) {@code cardinality} expression giving the number of instances.
 */
@Getter
@Setter
public class MultiInstanceExtensionModel {
    private boolean sequential;
    private String cardinality;
    /** Optional FEEL boolean: when it evaluates true after an instance completes, the multi-instance
     *  completes early (no further instances are started). */
    private String completionCondition;
    /** Camunda 8 {@code zeebe:loopCharacteristics}: the instance count is the size of this FEEL collection. */
    private String inputCollection;
    /** Per-instance variable name for the current collection element (requires scoped variables — pending). */
    private String inputElement;
    /** Variable to aggregate per-instance results into (requires scoped variables — pending). */
    private String outputCollection;
    /** FEEL expression for the per-instance result added to {@code outputCollection} (pending). */
    private String outputElement;
}
