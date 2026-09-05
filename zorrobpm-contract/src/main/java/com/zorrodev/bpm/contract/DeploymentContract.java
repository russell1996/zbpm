package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.DeployBatchDTO;
import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface DeploymentContract {

    /**
     * WO-C8-18: lays down a batch of resources (BPMN processes, DMN decisions) in ONE
     * transaction — either everything commits or nothing does. SUPER_ADMIN only (ADR-2),
     * enforced server-side like the BPMN/DMN single deploys.
     */
    @PostExchange(value = "/deployments", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    DeploymentDTO deployBatch(@RequestBody DeployBatchDTO dto);
}
