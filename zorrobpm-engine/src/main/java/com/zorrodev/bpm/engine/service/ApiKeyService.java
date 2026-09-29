package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.ApiKeyDTO;
import com.zorrodev.bpm.contract.dto.ApiKeyGrantDTO;
import com.zorrodev.bpm.contract.dto.ApiKeyWithSecretDTO;
import com.zorrodev.bpm.contract.dto.SetGrantsDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WO-DEBT-7 S3: JPA-backed API-key management, moved verbatim out of the
 * REST-layer {@code ApiKeyManagementResource} (which stays behind as a thin
 * facade: auth checks + delegation, {@code @Transactional} boundaries kept
 * exactly where they were). The web layer must not import
 * {@code engine.repository.*}/{@code engine.entity.*}; every read and every
 * {@code .save()} lives here, inside the caller's transaction (no
 * {@code @Transactional} of its own — same as the original location, proven by
 * {@code ApiKeyTransactionalIT}: audit failure rolls the save back).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX = "zbpm_sk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGrantRepository apiKeyGrantRepository;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final AuditLogService auditLogService;

    public ApiKeyDTO getApiKeyForUser(UUID userId) {
        if (isSystemAccount(userId)) {
            // WO-INT-4: a system account holds several keys — "the key of the user"
            // answers with the first active one (listApiKeys is the full view).
            return apiKeyRepository.findAllByOwnerUserId(userId).stream()
                .filter(k -> k.getRevokedAt() == null)
                .findFirst()
                .map(this::toDTO)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key for this user"));
        }
        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return toDTO(apiKey);
    }

    public ApiKeyDTO getOwnApiKey(UUID userId) {
        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return toDTO(apiKey);
    }

    /**
     * WO-SEC-67 (F13): is this API key still live? Same checks as
     * {@code JwtAuthFilter.resolveApiKey} (revoked → dead, expired → dead,
     * owner deactivated/deleted → dead — WO-ACL-5 criterion #4), extracted for
     * the SSE stream liveness gate so the REST layer never touches the key
     * repository directly (WO-DEBT-7 REST→JPA boundary).
     */
    public boolean isKeyLive(UUID apiKeyId) {
        var keyOpt = apiKeyRepository.findById(apiKeyId);
        if (keyOpt.isEmpty()) {
            return false;
        }
        ApiKeyEntity apiKey = keyOpt.get();
        if (apiKey.getRevokedAt() != null) {
            return false;
        }
        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(Instant.now())) {
            return false;
        }
        return uiUserRepository.findById(apiKey.getOwnerUserId())
            .map(UiUserEntity::isActive)
            .orElse(false);
    }

    public List<ApiKeyDTO> listKeysForUser(UUID userId) {
        return apiKeyRepository.findAllByOwnerUserId(userId).stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    /**
     * One key per user, shared by the super-admin, self-service and additional-key
     * paths: active key → 409, revoked key → replaced (old key + its grants
     * deleted, new key issued).
     * WO-INT-4: for a SYSTEM account the "one key" rule does not apply — a new key is
     * added alongside the existing ones (zero-downtime rotation); nothing is deleted.
     * (The duplicate 409-guard that lived in createAdditionalApiKey is subsumed here —
     * same query, same exception, same message — so it is not duplicated.)
     */
    public ApiKeyWithSecretDTO issueKeyForUser(UUID userId, Principal principal) {
        UiUserEntity user = uiUserRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        boolean system = "SYSTEM".equals(user.getUserType());

        if (!system) {
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
        }

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
        auditLogService.record(principal, "KEY_CREATE", null, userId.toString());
        return toWithSecret(apiKey, rawKey);
    }

    public List<ApiKeyGrantDTO> setGrantsForUser(UUID userId, SetGrantsDTO dto, Principal principal) {
        if (isSystemAccount(userId)) {
            // WO-INT-4 criterion 5: a system account's grants are account-level — every
            // active key of the account carries the same grants (rotation must not
            // produce a key with a different permission set).
            List<ApiKeyEntity> keys = apiKeyRepository.findAllByOwnerUserId(userId).stream()
                .filter(k -> k.getRevokedAt() == null)
                .toList();
            if (keys.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key for this user");
            }
            replaceGrants(keys.get(0), dto, principal); // validates once (process exists, membership, full/permissions)
            for (int i = 1; i < keys.size(); i++) {
                replaceGrants(keys.get(i), dto, principal);
            }
            return getGrants(keys.get(0).getId());
        }

        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return replaceGrants(apiKey, dto, principal);
    }

    /**
     * WO-ACL-5 criterion #2: a user sets grants for their own key. Same validation
     * as the super-admin path — shared implementation via {@link #replaceGrants},
     * not a copy.
     */
    public List<ApiKeyGrantDTO> setOwnGrants(UUID userId, SetGrantsDTO dto, Principal principal) {
        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        return replaceGrants(apiKey, dto, principal);
    }

    public ApiKeyWithSecretDTO rotateKeyForUser(UUID userId, Principal principal) {
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        String rawKey = generateKey();
        apiKey.setKeyHash(KeyHasher.sha256(rawKey));
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKeyRepository.save(apiKey);

        log.info("API key rotated for user={}", userId);
        auditLogService.record(principal, "KEY_ROTATE", null, userId.toString());
        return toWithSecret(apiKey, rawKey);
    }

    public ApiKeyWithSecretDTO rotateOwnKey(UUID userId) {
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        String rawKey = generateKey();
        apiKey.setKeyHash(KeyHasher.sha256(rawKey));
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKeyRepository.save(apiKey);

        return toWithSecret(apiKey, rawKey);
    }

    public void revokeKeyForUser(UUID userId, Principal principal) {
        // WO-INT-4: a system account may hold several active keys. Revoking "the key of the
        // user" then means revoking them all — key-level revocation lives in revokeKeyById.
        if (isSystemAccount(userId)) {
            List<ApiKeyEntity> keys = apiKeyRepository.findAllByOwnerUserId(userId);
            boolean any = false;
            for (ApiKeyEntity key : keys) {
                if (key.getRevokedAt() == null) {
                    key.setRevokedAt(Instant.now());
                    apiKeyRepository.save(key);
                    any = true;
                }
            }
            if (any) {
                auditLogService.record(principal, "KEY_REVOKE", null, userId.toString());
            }
            log.info("All API keys revoked for system user={}", userId);
            return;
        }
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);
        auditLogService.record(principal, "KEY_REVOKE", null, userId.toString());

        log.info("API key revoked for user={}", userId);
    }

    public void revokeOwnKey(UUID userId) {
        ApiKeyEntity apiKey = findByUserIdOr404(userId);
        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);
    }

    public void revokeKeyById(UUID userId, UUID apiKeyId, Principal principal) {
        ApiKeyEntity key = apiKeyRepository.findById(apiKeyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        if (!key.getOwnerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found");
        }
        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
        auditLogService.record(principal, "KEY_REVOKE", null, userId.toString());
        log.info("API key {} revoked for user={}", apiKeyId, userId);
    }

    /**
     * Validate-and-replace grants, shared by the super-admin and self-service paths:
     * each process must exist, the owner must be a member of it, full/permissions are
     * mutually exclusive. Any violation → 400 before any grant is touched.
     */
    private List<ApiKeyGrantDTO> replaceGrants(ApiKeyEntity apiKey, SetGrantsDTO dto, Principal principal) {
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
                new ProcessMemberId(process.getId(), apiKey.getOwnerUserId()));
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
        auditLogService.record(principal, "KEY_GRANTS_UPDATE", null, apiKey.getOwnerUserId().toString());
        return getGrants(apiKey.getId());
    }

    private ApiKeyEntity findByUserIdOr404(UUID userId) {
        return apiKeyRepository.findByOwnerUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key for this user"));
    }

    private boolean isSystemAccount(UUID userId) {
        return uiUserRepository.findById(userId)
            .map(u -> "SYSTEM".equals(u.getUserType()))
            .orElse(false);
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
