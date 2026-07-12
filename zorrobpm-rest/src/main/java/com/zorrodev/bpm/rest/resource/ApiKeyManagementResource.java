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
    private final HttpServletRequest request;

    // ==================== Super-admin endpoints ====================

    @Override
    public ApiKeyWithSecretDTO createApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();

        if (apiKeyRepository.findByOwnerUserId(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already has an API key");
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
        return toWithSecret(apiKey, rawKey);
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
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), userId));
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

        log.info("Grants updated for user={}, count={}", userId, dto.getGrants().size());
        return getGrants(apiKey.getId());
    }

    @Override
    public ApiKeyWithSecretDTO rotateApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        String rawKey = generateKey();
        apiKey.setKeyHash(KeyHasher.sha256(rawKey));
        apiKey.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        apiKeyRepository.save(apiKey);

        log.info("API key rotated for user={}", userId);
        return toWithSecret(apiKey, rawKey);
    }

    @Override
    public void revokeApiKey(@PathVariable UUID userId) {
        requireSuperAdmin();
        ApiKeyEntity apiKey = findByUserIdOr404(userId);

        apiKey.setRevokedAt(Instant.now());
        apiKeyRepository.save(apiKey);

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
