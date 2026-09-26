package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ProcessDefinitionService {

    Optional<ProcessDefinition> getProcessDefinitionById(UUID id);

    ProcessDefinition addProcessDefinition(String bpmn);

    /**
     * WO-C8-18: same as {@link #addProcessDefinition(String)}, but stamps {@code deploymentId}
     * on newly created version rows (batch deploys via {@code POST /deployments}). Null keeps
     * single-deploy behaviour unchanged.
     */
    ProcessDefinition addProcessDefinition(String bpmn, UUID deploymentId);

    PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters);

    PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters, Collection<UUID> allowedPdIds);

    /** WO-ENG-9: archive a process key (all versions hidden by default). */
    void archiveProcess(String key);

    /** WO-ENG-9: unarchive a process key. */
    void unarchiveProcess(String key);

    /**
     * WO-ENG-18: find-or-create the {@code process}-registry row for a deployed key.
     * Holds the invariant "every code ever deployed into {@code process_definitions}
     * owns exactly one row in {@code process"} in ONE place — called from
     * {@link #addProcessDefinition(String, java.util.UUID)}, the common point of ALL
     * deploy paths (single, batch, version-upload, submission-approve). Rows created
     * here carry NO owner (ADR-2 centralized control plane; authz stays fail-closed —
     * without a membership a non-admin is still DENY). Concurrent first-deploys of
     * the same key converge on the winner's row (unique {@code definition_key}).
     * Must run inside the caller's deploy transaction: a rolled-back deploy leaves
     * no orphaned registry row.
     */
    ProcessEntity ensureProcessRow(String definitionKey, String name);
}
