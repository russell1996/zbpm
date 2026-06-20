package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.event.model.VariableModel;

import java.util.List;

public interface ScriptService {

    Object evaluateScript(String script, List<ProcessVariable> variables);

    /** Evaluates a full FEEL expression (any return value), unlike {@link #evaluateScript} which runs a unary test. */
    Object evaluateExpression(String expression, List<ProcessVariable> variables);

}
