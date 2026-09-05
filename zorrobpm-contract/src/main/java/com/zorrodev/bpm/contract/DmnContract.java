package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.DeployDmnDTO;
import com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO;
import com.zorrodev.bpm.contract.model.DmnDecision;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.http.MediaType;

import java.util.List;

public interface DmnContract {

    /** All deployed decisions (latest version of each). */
    @GetExchange("/dmn")
    List<DmnDecision> getDecisions();

    @GetExchange("/dmn/{decisionId}")
    DmnDecision getDecision(@PathVariable String decisionId);

    /** Evaluates a decision against the given variables; returns a name→value map of its outputs. */
    @PostExchange("/dmn/{decisionId}/evaluate")
    Object evaluateDecision(@PathVariable String decisionId, @RequestBody EvaluateDecisionDTO dto);

    /**
     * WO-C8-15 (A-7): deploys a DMN resource (one file may carry several decisions).
     * Same shape as BPMN deploy — JSON body with the XML text, SUPER_ADMIN only server-side.
     * Returns the latest version of each decision after the deploy.
     */
    @PostExchange(value = "/dmn", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    List<DmnDecision> deployDmn(@RequestBody DeployDmnDTO dto);
}
