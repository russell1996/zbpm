package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.DeploymentContract;
import com.zorrodev.bpm.contract.dto.DeployedDecisionDTO;
import com.zorrodev.bpm.contract.dto.DeployedProcessDTO;
import com.zorrodev.bpm.contract.dto.DeployBatchDTO;
import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DeploymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
@Slf4j
public class DeploymentResource implements DeploymentContract {

    private final DeploymentService deploymentService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;

    /**
     * WO-C8-18: atomic batch deploy (BPMN processes + DMN decisions in one transaction).
     * ADR-2, exactly like the BPMN/DMN single deploys: SUPER_ADMIN only — no principal → 401,
     * non-admin → 403. DMN parse failures map to 400 like the single DMN path; BPMN failures
     * propagate exactly as the single BPMN path produces them (same service call).
     */
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentDTO deployBatch(@Valid @RequestBody DeployBatchDTO dto) {
        requireSuperAdmin();
        DeploymentDTO result;
        try {
            result = deploymentService.deployBatch(
                dto == null ? null : dto.getResources(),
                dto == null ? null : dto.getDescription(),
                deployedBy(getPrincipal()));
        } catch (EngineException e) {
            log.warn("Deployment batch failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deployment failed");
        }
        // Audit per deployed item, same shapes as the single paths (uniform audit views):
        // BPMN like ProcessDefinitionResource (key, pd id), DMN like DmnResource (decisionId, id:vN).
        for (DeployedProcessDTO process : result.getProcesses()) {
            auditLogService.record(getPrincipal(), "DEPLOY", process.getKey(), process.getProcessDefinitionId().toString());
        }
        for (DeployedDecisionDTO decision : result.getDecisions()) {
            auditLogService.record(getPrincipal(), "DEPLOY", decision.getDecisionId(),
                decision.getDecisionId() + ":v" + decision.getVersion());
        }
        // WO-API-1: nullable-guard как везде (unit без response-контекста).
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/deployments/"
                + (result.getProcesses().isEmpty() ? "batch" : result.getProcesses().get(0).getKey()));
        }
        return result;
    }

    /**
     * ADR-2 mechanism, copied 1:1 from {@code ProcessDefinitionResource.requireSuperAdmin} —
     * the same codes for the same cases (WO-C8-15 precedent: no weaker auth on deploy paths).
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

    private static String deployedBy(Principal principal) {
        if (principal instanceof Principal.UserPrincipal u) {
            return "user:" + u.username();
        }
        if (principal instanceof Principal.ServicePrincipal s) {
            return "apikey:" + s.apiKeyId();
        }
        return null;
    }
}
