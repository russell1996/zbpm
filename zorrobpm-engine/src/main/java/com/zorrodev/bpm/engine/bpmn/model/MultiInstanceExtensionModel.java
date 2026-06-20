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
}
