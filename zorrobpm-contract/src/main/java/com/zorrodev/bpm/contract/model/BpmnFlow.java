package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

/**
 * A sequence flow (edge) of a BPMN process: connects {@code sourceRef} to {@code targetRef},
 * optionally guarded by a {@code conditionExpression} (FEEL).
 */
@Getter
@Setter
public class BpmnFlow {
    private String id;
    private String name;
    private String sourceRef;
    private String targetRef;
    private String conditionExpression;
}
