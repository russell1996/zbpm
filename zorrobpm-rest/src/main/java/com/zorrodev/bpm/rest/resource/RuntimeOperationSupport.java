package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuntimeOperationSupport {

    /**
     * WO-SEC-64 (S-RBAC-3): strict format for X-On-Behalf-Of — a username, not
     * an arbitrary string. 1–64 chars, letters/digits plus {@code . _ - @}
     * (covers login names and email-style attributions, rejects markup/
     * control chars). Existence is verified right below in
     * {@link #checkedOnBehalfOf} via {@code requireOnBehalfExists} (fail-closed
     * 404); task-scoped matching stays in {@code requireOnBehalfMatchesTask}.
     */
    static final Pattern ON_BEHALF_OF_FORMAT =
        Pattern.compile("^[A-Za-z0-9._\\-@]{1,64}$");

    private final HttpServletRequest request;
    private final AuthorizationService authorizationService;
    private final RuntimeSupportService runtimeSupportService;

    public Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    /**
     * Read optional X-On-Behalf-Of header (WO-INT-2, WO-SEC-28, WO-INT-4).
     * Returns the raw trimmed value (max 255 chars) or null. The value itself is
     * NOT trusted yet — it must pass {@link #requireOnBehalfMatchesTask} before it
     * is used as an assignee, and audit records keep the "[claimed]" marker.
     */
    public String rawOnBehalfOf() {
        String val = request.getHeader("X-On-Behalf-Of");
        if (val == null || val.isBlank()) return null;
        String trimmed = val.trim();
        if (trimmed.length() > 255) trimmed = trimmed.substring(0, 255);
        return trimmed;
    }

    /**
     * WO-INT-4 criteria 9-10: X-On-Behalf-Of is accepted from ANY key. The rule is about the
     * authentication method, not the account type — a key is a key, whoever it was issued to
     * (WO-INT-4 §3). Returns the raw claimed username, or null when the header is absent.
     *
     * <p>WO-SEC-64 (S-RBAC-3): the claim must additionally match
     * {@link #ON_BEHALF_OF_FORMAT} — garbage never travels further — and name an
     * existing user ({@code requireOnBehalfExists}, fail-closed 404); a malformed
     * value is logged as a signal (possible probe).
     */
    public String checkedOnBehalfOf() {
        String raw = rawOnBehalfOf();
        if (raw == null) return null;
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!(principal instanceof Principal.ServicePrincipal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "X-On-Behalf-Of is only accepted from API keys");
        }
        if (!ON_BEHALF_OF_FORMAT.matcher(raw).matches()) {
            log.warn("Suspicious X-On-Behalf-Of value rejected: length={}", raw.length());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid X-On-Behalf-Of format");
        }
        // WO-SEC-64 HOLD (S-RBAC-3, вторая половина): существование принципала —
        // fail-closed 404 до любой работы. JPA живёт в сервисе (граница слоёв),
        // фасад только делегирует — как requireOnBehalfMatchesTask рядом.
        runtimeSupportService.requireOnBehalfExists(raw);
        return raw;
    }

    /**
     * WO-INT-4 criteria 9-10: when a service key claims an attribution, the claim must be
     * verifiable — delegates to {@link RuntimeSupportService} (JPA lives there, not here).
     */
    public void requireOnBehalfMatchesTask(String assignee, String candidateGroups,
            UUID processInstanceId, String username) {
        runtimeSupportService.requireOnBehalfMatchesTask(assignee, candidateGroups, processInstanceId, username);
    }

    public void requireOperate(String definitionKey, AuthorizationService.Action action) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (definitionKey == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        if (!authorizationService.canOperate(principal, definitionKey, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    /**
     * Definition-key resolution for authz — delegates to {@link RuntimeSupportService}
     * (JPA lives there, not here). Signatures unchanged, so existing callers keep working.
     */
    public String resolveDefinitionKeyByInstance(UUID instanceId) {
        return runtimeSupportService.resolveDefinitionKeyByInstance(instanceId);
    }

    public String resolveDefinitionKeyByServiceTask(UUID serviceTaskId) {
        return runtimeSupportService.resolveDefinitionKeyByServiceTask(serviceTaskId);
    }

    public String resolveDefinitionKeyByIncident(UUID incidentId) {
        return runtimeSupportService.resolveDefinitionKeyByIncident(incidentId);
    }

    public String resolvePrincipalId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal user) {
            return user.username();
        }
        if (principal instanceof Principal.ServicePrincipal sa) {
            return sa.apiKeyId().toString();
        }
        return principal.toString();
    }

    /**
     * Assignee check — delegates to {@link RuntimeSupportService} (JPA lives there).
     * Takes the task's scalar fields instead of the entity, so this facade never
     * touches entity types.
     */
    public void checkAssignee(Principal principal, String assignee, String candidateGroups,
            UUID processInstanceId) {
        runtimeSupportService.checkAssignee(principal, assignee, candidateGroups, processInstanceId);
    }

    public com.zorrodev.bpm.contract.dto.IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        com.zorrodev.bpm.contract.dto.IdDTO result = new com.zorrodev.bpm.contract.dto.IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
