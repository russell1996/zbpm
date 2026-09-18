package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;

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
}
