package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DmnService {

    /** Deploys a DMN resource, storing its XML by each contained decision id (latest deployment wins). */
    void deploy(String dmnXml);

    /** Deploys a DMN resource bound to a process definition (for authz scoping). */
    void deploy(String dmnXml, UUID processDefinitionId);

    /** Evaluates a deployed decision against the given variables and returns its single output value. */
    Object evaluate(String decisionId, List<ProcessVariable> variables);

    /** All deployed decisions (latest version of each), parsed for display. */
    List<DmnDecision> listDecisions();

    /** The latest version of a single decision, parsed for display. */
    DmnDecision getDecision(String decisionId);

    /**
     * All deployed decisions (latest version of each) scoped to the given allowed process
     * definition ids. {@code null} allowedPdIds = see all (superAdmin / full-grant).
     */
    List<DmnDecision> listDecisions(Collection<UUID> allowedPdIds);

    /**
     * The process definition id a decision is scoped to (empty if the decision is unknown).
     * Used by the REST layer to authorize get/evaluate by process access.
     */
    Optional<UUID> findProcessDefinitionId(String decisionId);
}
