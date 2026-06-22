package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO;
import com.zorrodev.bpm.contract.model.DmnDecision;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

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
}
