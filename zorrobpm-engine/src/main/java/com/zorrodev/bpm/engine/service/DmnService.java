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

    /**
     * WO-C8-18: same as {@link #deploy(String, UUID)}, but stamps {@code deploymentId} on the
     * created rows (batch deploys via {@code POST /deployments}). Null keeps single-deploy
     * behaviour byte-identical.
     */
    void deploy(String dmnXml, UUID processDefinitionId, UUID deploymentId);

    /** Evaluates a deployed decision against the given variables and returns its single output value. */
    Object evaluate(String decisionId, List<ProcessVariable> variables);

    /**
     * WO-DIFF-8: light existence probe for the execution path. A business rule task
     * referencing an undeployed decision must park a CALLED_DECISION_ERROR incident
     * (Zeebe/Raxon S-059 parity: the process stalls, the start is NOT rejected with
     * 422) — and the incident channel is {@code IllegalStateException}, never the
     * {@code EngineException} that {@link #evaluate(String, List)} throws. The plain
     * {@code evaluate} keeps its {@code EngineException} contract untouched (REST
     * {@code POST /dmn/{id}/evaluate} maps it to 400, existing DMN tests pin it) —
     * the handler pre-checks with this probe instead of changing the shared method.
     */
    boolean decisionExists(String decisionId);

    /**
     * WO-C8-20: evaluates the latest deployed version annotated with the given version tag
     * ({@code bindingType="versionTag"} pinning). Signature form (named method, not a third
     * overload or enum param): the two existing overloads stay byte-identical, and the call
     * site reads explicitly ({@code else if versionTag → evaluateByVersionTag}).
     */
    Object evaluateByVersionTag(String decisionId, List<ProcessVariable> variables, String versionTag);

    /**
     * WO-C8-17: evaluates the decision version deployed together with the given process
     * definition version ({@code bindingType="deployment"} pinning). A null
     * {@code pinnedProcessDefinitionId} behaves exactly like {@link #evaluate(String, List)}.
     */
    Object evaluate(String decisionId, List<ProcessVariable> variables, UUID pinnedProcessDefinitionId);

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
