package com.zorrodev.bpm.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * WO-SEC-74 (N06): re-authorize an idempotency replay against the CURRENT policy
 * before the cached response is served.
 *
 * <p>{@code IdempotencyFilter} runs after credential auth but historically returned
 * the cached hit before any resource/object-level check — a still-valid credential
 * of an actor whose grants were narrowed/revoked AFTER the first request would get
 * the old cached response of an operation the live path would now deny. The business
 * mutation is not repeated (replay serves bytes, not effects), but the stale
 * response leaks saved IDs and falsely confirms "operation allowed".
 *
 * <p>This bean mirrors the live controllers' check sequences (same
 * {@link AuthorizationService} methods, same existence-then-authz order: missing
 * resource → 404, present-but-forbidden → 403). It lives in the engine module so
 * the REST layer gains no new {@code engine.repository}/{@code engine.entity}
 * imports (WO-DEBT-7 REST→JPA boundary).
 *
 * <p>Fail-closed everywhere: an unresolvable endpoint/UUID/body denies (403), never
 * serves the cache. {@code /auth/register} is the only public path and returns
 * without checks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyReplayAuthorizer {

    private final AuthorizationService authorizationService;
    private final RuntimeSupportService runtimeSupportService;
    private final UserTaskRepository userTaskRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * @param principal current request principal (from the {@code principal} request
     *                  attribute — the same source the controllers use)
     * @param endpoint  normalized endpoint the record was stored under
     * @param onBehalfOf trimmed {@code X-On-Behalf-Of} value, or null when absent
     * @param body      raw request body (for start/publish key resolution)
     * @throws ResponseStatusException 401/403/404/400 when the replay must NOT be served
     */
    public void authorizeReplay(Principal principal, String endpoint, String onBehalfOf, byte[] body) {
        if ("/auth/register".equals(endpoint)) {
            return; // public, unauthenticated — nothing to re-check
        }
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        switch (endpoint) {
            case "/deployments", "/dmn", "/forms" -> requireSuperAdmin(principal);
            case "/process-instances" -> authorizeStart(principal, onBehalfOf, body);
            case "/messages/publish" -> authorizePublish(principal, body);
            default -> authorizeMutation(principal, endpoint, onBehalfOf);
        }
    }

    private void requireSuperAdmin(Principal principal) {
        // Mirrors DeploymentResource/DmnResource/FormOperationsImpl deploy gates:
        // SUPER_ADMIN only, no object-level dimension to narrow over time — but the
        // flag itself is re-read from the CURRENT principal (a demoted ex-admin replays 403).
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }
    }

    private void authorizeStart(Principal principal, String onBehalfOf, byte[] body) {
        // Mirrors ProcessInstanceRuntimeOperationsImpl.startProcessInstance: resolve the
        // definition key, then require START. DTO parse failure → 400 (live path would
        // 400 on unreadable JSON via message conversion, before any authz).
        final StartProcessInstanceDTO dto;
        try {
            dto = MAPPER.readValue(body, StartProcessInstanceDTO.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable request body");
        }
        ProcessDefinitionEntity target = runtimeSupportService.resolveTargetDefinition(dto);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found");
        }
        // Red-team HOLD-F1 (WO-SEC-74): the live start path checks OBO FIRST
        // (ProcessInstanceRuntimeOperationsImpl:58 — format/non-service/existence
        // before the START grant). A replay must not serve stale bytes when the
        // claimed user was deleted since: re-check existence against current state.
        // Mapped to 403 (not the live 404 — verifier #5) deliberately: the replay
        // denial must not become a user-existence oracle distinct from the live
        // path's own codes, and every other recheck in this class is 403 too.
        // Format/non-service nuances stay live-path-only; this closes exactly the
        // policy-narrowing gap (deleted user → replay denied).
        if (onBehalfOf != null) {
            try {
                runtimeSupportService.requireOnBehalfExists(onBehalfOf);
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
        }
        requireOperate(principal, target.getKey(), AuthorizationService.Action.START);
    }

    private void authorizePublish(Principal principal, byte[] body) {
        // Mirrors MessageRuntimeOperationsImpl.publishMessage: instance-scoped →
        // CORRELATE_MESSAGE on the instance's definition key; global (name-only) →
        // SUPER_ADMIN only (fail-closed, no silent partial fan-out).
        final PublishMessageDTO dto;
        try {
            dto = MAPPER.readValue(body, PublishMessageDTO.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable request body");
        }
        if (dto.getProcessInstanceId() != null) {
            String key = runtimeSupportService.resolveDefinitionKeyByInstance(dto.getProcessInstanceId());
            if (key == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
            }
            requireOperate(principal, key, AuthorizationService.Action.CORRELATE_MESSAGE);
            return;
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private void authorizeMutation(Principal principal, String endpoint, String onBehalfOf) {
        if (endpoint.startsWith("/user-tasks/") && endpoint.endsWith("/complete")) {
            UserTaskEntity task = userTaskOf(endpoint, 1);
            if (!authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(),
                    task.getCandidateGroups())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            // Same second gate as UserTaskRuntimeOperationsImpl.completeUserTask.
            try {
                runtimeSupportService.checkAssignee(principal, task.getAssignee(),
                    task.getCandidateGroups(), task.getProcessInstanceId());
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            recheckOnBehalf(principal, task, onBehalfOf, true);
            return;
        }
        if ((endpoint.startsWith("/user-tasks/") && endpoint.endsWith("/claim"))
                || (endpoint.startsWith("/user-tasks/") && endpoint.endsWith("/unclaim"))) {
            UserTaskEntity task = userTaskOf(endpoint, 1);
            if (!authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(),
                    task.getCandidateGroups())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            recheckOnBehalf(principal, task, onBehalfOf, endpoint.endsWith("/claim"));
            return;
        }
        if (endpoint.startsWith("/user-tasks/") && endpoint.endsWith("/assign")) {
            UserTaskEntity task = userTaskOf(endpoint, 1);
            if (!authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            return;
        }
        if (endpoint.startsWith("/service-tasks/")) {
            UUID id = lastUuidOf(endpoint, 1);
            String key = runtimeSupportService.resolveDefinitionKeyByServiceTask(id);
            if (key == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
            }
            requireOperate(principal, key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
            return;
        }
        if (endpoint.startsWith("/incidents/") && endpoint.endsWith("/resolve")) {
            UUID id = lastUuidOf(endpoint, 1);
            // Mirrors IncidentRuntimeOperationsImpl: resolve gates on COMPLETE_SERVICE_TASK.
            String key = runtimeSupportService.resolveDefinitionKeyByIncident(id);
            if (key == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
            }
            requireOperate(principal, key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
            return;
        }
        if (endpoint.startsWith("/process-instances/") && endpoint.endsWith("/cancel")) {
            UUID id = lastUuidOf(endpoint, 1);
            // Mirrors ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance.
            String key = runtimeSupportService.resolveDefinitionKeyByInstance(id);
            if (key == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
            }
            requireOperate(principal, key, AuthorizationService.Action.DELETE_PROCESS);
            return;
        }
        // Unknown idempotent endpoint — fail closed, never serve the cache.
        log.warn("Idempotency replay of unknown endpoint denied: {}", endpoint);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * Re-check the {@code X-On-Behalf-Of} attribution claim the live complete/claim
     * path verified (WO-INT-4 criterion 9). The claim travels in the fingerprint, so
     * a replay normally carries the same claim — this recheck covers the case where
     * the named user was deleted/demoted since, or the task's assignee/candidates
     * changed. Unclaim takes no attribution (nothing to recheck).
     */
    private void recheckOnBehalf(Principal principal, UserTaskEntity task, String onBehalfOf,
            boolean attributionApplies) {
        if (onBehalfOf == null || !attributionApplies) return;
        if (!(principal instanceof Principal.ServicePrincipal)) {
            // Live path rejects OBO from non-keys with 403 — a replay carrying one
            // for a user principal must not serve either.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        try {
            runtimeSupportService.requireOnBehalfMatchesTask(task.getAssignee(),
                task.getCandidateGroups(), task.getProcessInstanceId(), onBehalfOf);
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private UserTaskEntity userTaskOf(String endpoint, int uuidSegmentFromEnd) {
        UUID id = lastUuidOf(endpoint, uuidSegmentFromEnd);
        return userTaskRepository.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));
    }

    private static UUID lastUuidOf(String endpoint, int fromEnd) {
        String[] parts = endpoint.split("/");
        // "/user-tasks/{id}/complete" → ["", "user-tasks", "{id}", "complete"]; the UUID
        // sits `fromEnd` segments before the trailing action (1 = ".../{id}/action").
        String raw = parts[parts.length - 1 - fromEnd];
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // Live Spring MVC mapping would 400 on a malformed UUID path variable.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource id");
        }
    }

    private void requireOperate(Principal principal, String definitionKey,
            AuthorizationService.Action action) {
        if (!authorizationService.canOperate(principal, definitionKey, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }
}
