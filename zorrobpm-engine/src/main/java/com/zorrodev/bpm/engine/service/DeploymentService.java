package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;

import java.util.List;

/**
 * WO-C8-18: atomic multi-resource deployments (the enabler for WO-C8-3b
 * {@code bindingType="deployment"} on call activities — "the version deployed <i>together
 * with</i> the currently running process version" needs the notion of <i>together</i>).
 */
public interface DeploymentService {

    /**
     * Lays down a batch of resources in ONE transaction: BPMN processes first (DMN binding
     * needs their ids; deploy paths do not validate cross-references, so order does not
     * affect success — only linkage data), then DMN decisions bound to the first process
     * of the batch (or unbound when the batch carries no BPMN). Any failure rolls back
     * the whole batch — no half-deployed state.
     *
     * @param resources   BPMN/DMN items, non-empty, known types, non-blank content
     * @param description caller-supplied batch description (nullable)
     * @param deployedBy  principal label for the deployment row (nullable)
     */
    DeploymentDTO deployBatch(List<DeploymentItemDTO> resources, String description, String deployedBy);
}
