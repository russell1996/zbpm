package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Execution model for a business rule task: either a DMN {@code decisionId} (evaluated by the DMN engine)
 * or an inline FEEL {@code expression}; the result is written to {@code resultVariable}.
 */
@Getter
@Setter
public class BusinessRuleExtensionModel {
    private String decisionId;
    private String expression;
    private String resultVariable;
    /** WO-C8-17: DMN version binding ("latest" default, "deployment" pins to the process version). */
    private String bindingType;
    /**
     * WO-C8-17: parsed here; consumed since WO-C8-20 (see CalledDecisionModel).
     */
    private String versionTag;
}
