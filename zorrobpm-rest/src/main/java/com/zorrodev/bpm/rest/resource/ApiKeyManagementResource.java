package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ApiKeyManagementContract;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ApiKeyManagementResource implements ApiKeyManagementContract {

    private static final String KEY_PREFIX = "zbpm_sk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGrantRepository apiKeyGrantRepository;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;

    // ==================== Super-admin endpoints ====================

    @Transactional
    @Override
    public ApiKeyWithSecretDTO createApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        return issueKeyForUser(userId);
    }

    @Override
    public ApiKeyDTO getApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return toDTO(apiKey);
    }

    @Transactional
    @Override
    public List<ApiKeyGrantDTO> setGrants(@PathVariable UUID userId, @RequestBody SetGrantsDTO dto) {
        requireSuperAdmin();

        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return replaceGrants(apiKey, dto);
    }

    @Transactional
    @Override
    public ApiKeyWithSecretDTO rotateApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        String rawKey = generateKey();
        apiKey.setKeyHash(KeyHasher.sha256(rawKey));
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKeyRepository.save(apiKey);

        log.info("API key rotated for user={}", userId);
        auditLogService.record(getPrincipal(), "KEY_ROTATE", null, userId.toString());
        return toWithSecret(apiKey, rawKey);
    }

    @Transactional
    @Override
    public void revokeApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);
        auditLogService.record(getPrincipal(), "KEY_REVOKE", null, userId.toString());

        log.info("API key revoked for user={}", userId);
    }

    // ==================== User self-service endpoints ====================

    @Override
    public ApiKeyDTO getMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        ApiKeyEntity apiKey = findByUserIdOr404(u.userId());
        return toDTO(apiKey);
    }

    /**
     * WO-ACL-5 criterion #1: a user issues their own API key. Same one-key-per-user
     * semantics as the super-admin path (409 while an active key exists, replacement
     * after revoke) — shared implementation, not a copy.
     */
    @Transactional
    @Override
    public ApiKeyWithSecretDTO createMyApiKey() {
        Principal.UserPrincipal user = selfPrincipal();
        return issueKeyForUser(user.userId());
    }

    /**
     * WO-ACL-5 criterion #2: a user sets grants for their own key. Grant validation
     * is the same as the super-admin path (process must exist, user must be a member,
     * full/permissions are mutually exclusive) — shared implementation, not a copy.
     */
    @Transactional
    @Override
    public List<ApiKeyGrantDTO> setMyGrants(@RequestBody SetGrantsDTO dto) {
        Principal.UserPrincipal user = selfPrincipal();
        ApiKeyEntity apiKey = findByUserIdOr404(user.userId());
        return replaceGrants(apiKey, dto);
    }

    @Transactional
    @Override
    public ApiKeyWithSecretDTO rotateMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        ApiKeyEntity apiKey = findByUserIdOr404(u.userId());

        String rawKey = generateKey();
        apiKey.setKeyHash(KeyHasher.sha256(rawKey));
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKeyRepository.save(apiKey);

        return toWithSecret(apiKey, rawKey);
    }

    @Transactional
    @Override
    public void revokeMyApiKey() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        ApiKeyEntity apiKey = findByUserIdOr404(u.userId());
        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);
    }

    // ==================== Helpers ====================

    /** WO-ACL-5: current caller as a UserPrincipal, or 401. Shared by all self-service endpoints. */
    private Principal.UserPrincipal selfPrincipal() {
        Principal principal = getPrincipal();
        if (principal == null || !(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return u;
    }

    /**
     * One key per user, shared by the super-admin and self-service create paths:
     * active key → 409, revoked key → replaced (old key + its grants deleted, new key issued).
     */
    private ApiKeyWithSecretDTO issueKeyForUser(UUID userId) {
        var existingKey = apiKeyRepository.findByOwnerUserId(userId);
        if (existingKey.isPresent()) {
            ApiKeyEntity existing = existingKey.get();
            if (existing.getRevokedAt() == null) {
                // Active key → 409
                throw new ResponseStatusException(HttpStatus.CONFLICT, "User already has an active API key");
            }
            // Revoked key → replace it (delete old + its grants, create new)
            apiKeyGrantRepository.deleteByApiKeyId(existing.getId());
            apiKeyRepository.delete(existing);
            apiKeyRepository.flush();
        }

        UiUserEntity user = uiUserRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String rawKey = generateKey();
        String keyHash = KeyHasher.sha256(rawKey);

        ApiKeyEntity apiKey = new ApiKeyEntity();
        apiKey.setId(UUID.randomUUID());
        apiKey.setOwnerUserId(userId);
        apiKey.setKeyHash(keyHash);
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKey.setCreatedAt(Instant.now());
        apiKeyRepository.save(apiKey);

        log.info("API key created for user={}", user.getUsername());
        auditLogService.record(getPrincipal(), "KEY_CREATE", null, userId.toString());
        return toWithSecret(apiKey, rawKey);
    }

    /**
     * Validate-and-replace grants, shared by the super-admin and self-service paths:
     * each process must exist, the owner must be a member of it, full/permissions are
     * mutually exclusive. Any violation → 400 before any grant is touched.
     */
    private List<ApiKeyGrantDTO> replaceGrants(ApiKeyEntity apiKey, SetGrantsDTO dto) {
        // Validate grants: each process must exist, user must be member
        for (SetGrantsDTO.GrantEntry entry : dto.getGrants()) {
            ProcessEntity process = processRepository.findByDefinitionKey(entry.getProcessKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Process not found: " + entry.getProcessKey()));

            if (entry.isFull() && (entry.getPermissions() != null && !entry.getPermissions().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot specify both permissions and full=true");
            }

            // Validate user is member of the process
            var membership = processMemberRepository.findById(
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), apiKey.getOwnerUserId()));
            if (membership.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "User is not a member of process: " + entry.getProcessKey());
            }
        }

        // Delete existing grants and insert new ones
        apiKeyGrantRepository.deleteByApiKeyId(apiKey.getId());

        for (SetGrantsDTO.GrantEntry entry : dto.getGrants()) {
            ProcessEntity process = processRepository.findByDefinitionKey(entry.getProcessKey()).orElseThrow();

            ApiKeyGrantEntity grant = new ApiKeyGrantEntity();
            grant.setApiKeyId(apiKey.getId());
            grant.setProcessId(process.getId());
            grant.setPermissions(entry.getPermissions());
            grant.setFull(entry.isFull());
            apiKeyGrantRepository.save(grant);
        }

        log.info("Grants updated for user={}, count={}", apiKey.getOwnerUserId(), dto.getGrants().size());
        auditLogService.record(getPrincipal(), "KEY_GRANTS_UPDATE", null, apiKey.getOwnerUserId().toString());
        return getGrants(apiKey.getId());
    }

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

    private ApiKeyEntity findByUserIdOr404(UUID userId) {
        return apiKeyRepository.findByOwnerUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key for this user"));
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ApiKeyDTO toDTO(ApiKeyEntity apiKey) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setId(apiKey.getId());
        dto.setOwnerUserId(apiKey.getOwnerUserId());
        dto.setPrefix(apiKey.getPrefix());
        dto.setCreatedAt(apiKey.getCreatedAt());
        dto.setLastUsedAt(apiKey.getLastUsedAt());
        dto.setExpiresAt(apiKey.getExpiresAt());
        dto.setRevokedAt(apiKey.getRevokedAt());
        dto.setGrants(getGrants(apiKey.getId()));
        return dto;
    }

    private ApiKeyWithSecretDTO toWithSecret(ApiKeyEntity apiKey, String rawKey) {
        ApiKeyWithSecretDTO dto = new ApiKeyWithSecretDTO();
        dto.setId(apiKey.getId());
        dto.setOwnerUserId(apiKey.getOwnerUserId());
        dto.setPrefix(apiKey.getPrefix());
        dto.setKey(rawKey);
        dto.setCreatedAt(apiKey.getCreatedAt());
        dto.setGrants(getGrants(apiKey.getId()));
        return dto;
    }

    private List<ApiKeyGrantDTO> getGrants(UUID apiKeyId) {
        return apiKeyGrantRepository.findByApiKeyId(apiKeyId).stream()
            .map(g -> {
                ApiKeyGrantDTO dto = new ApiKeyGrantDTO();
                dto.setProcessId(g.getProcessId());
                dto.setPermissions(g.getPermissions());
                dto.setFull(g.isFull());
                // Resolve processKey
                processRepository.findById(g.getProcessId()).ifPresent(p -> dto.setProcessKey(p.getDefinitionKey()));
                return dto;
            })
            .collect(Collectors.toList());
    }
}
