package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Execution model for a script task: the FEEL {@code script} to evaluate and the optional
 * {@code resultVariable} its result is stored in.
 */
@Getter
@Setter
public class ScriptTaskExtensionModel {
    private String scriptFormat;
    private String script;
    private String resultVariable;
}
