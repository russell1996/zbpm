package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.DmnContract;
import com.zorrodev.bpm.contract.dto.DeployDmnDTO;
import com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DmnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
@Slf4j
public class DmnResource implements DmnContract {

    private final DmnService dmnService;
    private final EventAuthzResolver eventAuthzResolver;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;
    private final AuditLogService auditLogService;

    /**
     * WO-SEC-40: resolves the process definition ids the current principal may access.
     * {@code null} = see all (superAdmin / full-grant), empty = see nothing (DENY).
     * Reuses the shared EventAuthzResolver (same mechanism as ProcessDefinitionResource).
     */
    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.visibleDefinitionIds(principal, null);
    }

    /**
     * WO-SEC-40: throws 404 if the current principal cannot access the decision's owning
     * process definition. 404 (not 403) keeps decision existence hidden from unauthorized
     * principals (same pattern as ProcessDefinitionResource.requirePdAccess).
     */
    private void requireDecisionAccess(String decisionId) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed == null) {
            return; // see all (superAdmin / full grant)
        }
        UUID owningPdId = dmnService.findProcessDefinitionId(decisionId).orElse(null);
        if (owningPdId == null || !allowed.contains(owningPdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        }
    }

    @Override
    public List<DmnDecision> getDecisions() {
        return dmnService.listDecisions(resolveAllowedPdIds());
    }

    /**
     * WO-C8-15 (A-7): DMN deploy — the missing production path for getting decisions into the
     * engine (previously only tests called {@code DmnService.deploy}).
     * ADR-2, exactly like BPMN deploy ({@code ProcessDefinitionResource.addProcessDefinition}):
     * SUPER_ADMIN only — no principal → 401, non-admin → 403. An endpoint uploading executable
     * logic must not be weaker than the BPMN one.
     */
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public List<DmnDecision> deployDmn(@Valid @RequestBody DeployDmnDTO dto) {
        requireSuperAdmin();
        Map<String, Integer> before;
        try {
            before = maxVersions(dmnService.listDecisions(null));
            dmnService.deploy(dto.getDmn(), dto.getProcessDefinitionId());
        } catch (EngineException e) {
            log.warn("DMN deploy failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DMN deployment failed");
        }
        List<DmnDecision> after = dmnService.listDecisions(null);
        // WO-C8-17 (debt from WO-C8-15): audit every newly deployed decision version, like
        // BPMN deploy does in both its places. One record per decision version —
        // key = decisionId, target = decisionId:vN (the entity UUID is not exposed in the DTO).
        // Only rows whose version actually advanced are recorded (the list also carries old ones).
        for (DmnDecision decision : after) {
            if (decision.getVersion() > before.getOrDefault(decision.getId(), 0)) {
                auditLogService.record(getPrincipal(), "DEPLOY", decision.getId(),
                    decision.getId() + ":v" + decision.getVersion());
            }
        }
        // WO-API-1: Location по первой задеплоенной decision (nullable-guard как везде).
        if (httpResponse != null && !after.isEmpty()) {
            httpResponse.setHeader("Location", "/dmn/" + after.get(0).getId());
        }
        return after;
    }

    private static Map<String, Integer> maxVersions(List<DmnDecision> decisions) {
        Map<String, Integer> max = new HashMap<>();
        for (DmnDecision decision : decisions) {
            max.merge(decision.getId(), decision.getVersion(), Math::max);
        }
        return max;
    }

    /**
     * ADR-2 mechanism, copied 1:1 from {@code ProcessDefinitionResource.requireSuperAdmin} —
     * the same codes for the same cases (WO-C8-15: no weaker auth on the DMN path).
     */
    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    @Override
    public DmnDecision getDecision(@PathVariable String decisionId) {
        requireDecisionAccess(decisionId);
        try {
            return dmnService.getDecision(decisionId);
        } catch (EngineException e) {
            log.warn("DMN decision not found: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        }
    }

    @Override
    public Object evaluateDecision(@PathVariable String decisionId, @Valid @RequestBody EvaluateDecisionDTO dto) {
        requireDecisionAccess(decisionId);
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
