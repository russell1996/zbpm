package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.BpmnProcessStructure;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only inspection of a deployed BPMN definition: parses the stored XML into a nested,
 * UI-friendly structure (events/tasks/gateways/sub-processes with children and attached boundary
 * events). Completely independent of the execution engine.
 */
public interface BpmnStructureService {

    /** Empty when no definition with that id exists. */
    Optional<BpmnProcessStructure> getStructure(UUID processDefinitionId);
}
