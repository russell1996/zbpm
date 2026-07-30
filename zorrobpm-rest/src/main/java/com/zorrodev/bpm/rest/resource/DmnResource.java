package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.DmnContract;
import com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.engine.service.DmnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class DmnResource implements DmnContract {

    private final DmnService dmnService;

    @Override
    public List<DmnDecision> getDecisions() {
        return dmnService.listDecisions();
    }

    @Override
    public DmnDecision getDecision(@PathVariable String decisionId) {
        try {
            return dmnService.getDecision(decisionId);
        } catch (EngineException e) {
            log.warn("DMN decision not found: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        }
    }

    @Override
    public Object evaluateDecision(@PathVariable String decisionId, @RequestBody EvaluateDecisionDTO dto) {
        Object result;
        try {
            result = dmnService.evaluate(decisionId, dto.getVariables());
        } catch (EngineException e) {
            log.warn("DMN evaluation error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision evaluation failed");
        }
        // always return a name -> value object for the UI; a single-output decision is wrapped under "result"
        return result instanceof Map ? result : Map.of("result", result == null ? "" : result);
    }
}
