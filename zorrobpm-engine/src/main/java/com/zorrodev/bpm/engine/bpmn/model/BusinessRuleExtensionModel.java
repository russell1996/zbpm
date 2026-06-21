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
}
