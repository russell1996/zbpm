package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ApiKeyManagementContract;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

/**
 * WO-DEBT-7 S3 — thin facade over {@link ApiKeyService}: auth checks +
 * delegation. All JPA (reads and every {@code .save()}) lives in the service,
 * inside this class' transaction (no {@code @Transactional} on the service —
 * same as the original layout, proven by {@code ApiKeyTransactionalIT}: audit
 * failure rolls the save back). Zero direct persistence imports.
 */
@RestController
@RequiredArgsConstructor
public class ApiKeyManagementResource implements ApiKeyManagementContract {

    private final ApiKeyService apiKeyService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;
    /** WO-SEC-67 (F13): close live SSE streams on key revoke (credential behind them is dead). */
    private final SseEventStreamService sseEventStreamService;

    // ==================== Super-admin endpoints ====================

    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Transactional
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyWithSecretDTO createApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyWithSecretDTO result = apiKeyService.issueKeyForUser(userId, getPrincipal());
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/admin/users/" + userId + "/api-keys/" + result.getId());
        }
        return result;
    }

    @Override
    public ApiKeyDTO getApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        return apiKeyService.getApiKeyForUser(userId);
    }

    @Transactional
    @Override
    public List<ApiKeyGrantDTO> setGrants(@PathVariable UUID userId, @Valid @RequestBody SetGrantsDTO dto) {
        requireSuperAdmin();
        List<ApiKeyGrantDTO> result = apiKeyService.setGrantsForUser(userId, dto, getPrincipal());
        // WO-SEC-67 verifier HOLD #2: сужение/расширение грантов обязано
        // переоценить открытые key-потоки немедленно (live-view закроет
        // суженные на sweep; без hook idle-потоки висели бы до события).
        sseEventStreamService.invalidateStreams();
        return result;
    }

    @Transactional
    @Override
    public ApiKeyWithSecretDTO rotateApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyWithSecretDTO result = apiKeyService.rotateKeyForUser(userId, getPrincipal());
        // WO-SEC-67 red-team #2: rotation replaces the key MATERIAL in place
        // (same row — the generic sweep would see a live row and do nothing).
        // Close streams on this key id explicitly and deterministically.
        sseEventStreamService.invalidateStreamsForKey(result.getId());
        return result;
    }

    @Transactional
    @Override
    public void revokeApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        apiKeyService.revokeKeyForUser(userId, getPrincipal());
        // WO-SEC-67 (F13): the key behind open streams just died — close them now.
        sseEventStreamService.invalidateStreams();
    }

    // ==================== User self-service endpoints ====================

    @Override
    public ApiKeyDTO getMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        return apiKeyService.getOwnApiKey(u.userId());
    }

    /**
     * WO-ACL-5 criterion #1: a user issues their own API key. Same one-key-per-user
     * semantics as the super-admin path (409 while an active key exists, replacement
     * after revoke) — shared implementation, not a copy.
     */
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Transactional
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyWithSecretDTO createMyApiKey() {
        Principal.UserPrincipal user = selfPrincipal();
        ApiKeyWithSecretDTO result = apiKeyService.issueKeyForUser(user.userId(), user);
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/me/api-key");
        }
        return result;
    }

    /**
     * WO-ACL-5 criterion #2: a user sets grants for their own key. Grant validation
     * is the same as the super-admin path (process must exist, user must be a member,
     * full/permissions are mutually exclusive) — shared implementation, not a copy.
     */
    @Transactional
    @Override
    public List<ApiKeyGrantDTO> setMyGrants(@Valid @RequestBody SetGrantsDTO dto) {
        Principal.UserPrincipal user = selfPrincipal();
        List<ApiKeyGrantDTO> result = apiKeyService.setOwnGrants(user.userId(), dto, user);
        // WO-SEC-67 verifier HOLD #2: см. setGrants выше — тот же hook.
        sseEventStreamService.invalidateStreams();
        return result;
    }

    @Transactional
    @Override
    public ApiKeyWithSecretDTO rotateMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        ApiKeyWithSecretDTO result = apiKeyService.rotateOwnKey(u.userId());
        // WO-SEC-67 red-team #2: rotation replaces the key MATERIAL in place
        // (same row — the generic sweep would see a live row and do nothing).
        sseEventStreamService.invalidateStreamsForKey(result.getId());
        return result;
    }

    @Transactional
    @Override
    public void revokeMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        apiKeyService.revokeOwnKey(u.userId());
        // WO-SEC-67 (F13): the key behind open streams just died — close them now.
        sseEventStreamService.invalidateStreams();
    }

    // ==================== WO-INT-4: system accounts — multiple keys ====================

    @Override
    public List<ApiKeyDTO> listApiKeys(@PathVariable UUID userId) {
        requireSuperAdmin();
        return apiKeyService.listKeysForUser(userId);
    }

    @Transactional
    @Override
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyWithSecretDTO createAdditionalApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        // WO-INT-4 criterion 5: multiple concurrent keys are a SYSTEM-account feature
        // (zero-downtime rotation). Human accounts keep one-key-per-user: an attempt to
        // open a second key through this endpoint → 409 — enforced inside
        // issueKeyForUser (same query, same exception, same message as the removed
        // duplicate guard here).
        ApiKeyWithSecretDTO result = apiKeyService.issueKeyForUser(userId, getPrincipal());
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/admin/users/" + userId + "/api-keys/" + result.getId());
        }
        return result;
    }

    @Transactional
    @Override
    public void revokeApiKeyById(@PathVariable UUID userId, @PathVariable UUID apiKeyId) {
        requireSuperAdmin();
        apiKeyService.revokeKeyById(userId, apiKeyId, getPrincipal());
        // WO-SEC-67 (F13): the key behind open streams just died — close them now.
        sseEventStreamService.invalidateStreams();
    }

    // ==================== Helpers (no JPA) ====================

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }

    /** WO-ACL-5: current caller as a UserPrincipal, or 401. Shared by all self-service endpoints. */
    private Principal.UserPrincipal selfPrincipal() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return u;
    }
}
