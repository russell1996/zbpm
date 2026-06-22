package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;

public interface DmnService {

    /** Deploys a DMN resource, storing its XML by each contained decision id (latest deployment wins). */
    void deploy(String dmnXml);

    /** Evaluates a deployed decision against the given variables and returns its single output value. */
    Object evaluate(String decisionId, List<ProcessVariable> variables);

    /** All deployed decisions (latest version of each), parsed for display. */
    List<DmnDecision> listDecisions();

    /** The latest version of a single decision, parsed for display. */
    DmnDecision getDecision(String decisionId);
}
