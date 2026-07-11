package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ServiceAccountContract;
import com.zorrodev.bpm.contract.dto.CreateServiceAccountDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ServiceAccountDTO;
import com.zorrodev.bpm.contract.dto.ServiceAccountWithKeyDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ServiceAccountEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceAccountRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.repository.ServiceAccountPermissionRepository;
import com.zorrodev.bpm.engine.entity.ServiceAccountPermissionEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class ServiceAccountResource implements ServiceAccountContract {

    private final ProcessRepository processRepository;
    private final ServiceAccountRepository serviceAccountRepository;
    private final ServiceAccountPermissionRepository saPermissionRepository;
    private final AuthorizationService authorizationService;
    private final HttpServletRequest request;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "zbpm_sk_";

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireManageKeys(String processKey) {
        Principal principal = getPrincipal();
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (!authorizationService.canOperate(principal, processKey, AuthorizationService.Action.MANAGE_KEYS)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private ProcessEntity resolveProcess(String key) {
        return processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found"));
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    @Override
    public ServiceAccountWithKeyDTO createServiceAccount(@PathVariable String key, @RequestBody CreateServiceAccountDTO dto) {
        requireManageKeys(key);
        ProcessEntity process = resolveProcess(key);

        String rawKey = generateKey();
        String keyHash = KeyHasher.sha256(rawKey);
        String prefix = rawKey.substring(0, Math.min(16, rawKey.length()));

        ServiceAccountEntity sa = new ServiceAccountEntity();
        sa.setId(UUID.randomUUID());
        sa.setProcessId(process.getId());
        sa.setName(dto.getName());
        sa.setKeyHash(keyHash);
        sa.setPrefix(prefix);
        sa.setCreatedAt(Instant.now());
        serviceAccountRepository.save(sa);

        // Save permissions
        if (dto.getPermissions() != null) {
            for (String perm : dto.getPermissions()) {
                ServiceAccountPermissionEntity p = new ServiceAccountPermissionEntity();
                p.setServiceAccountId(sa.getId());
                p.setPermission(perm);
                saPermissionRepository.save(p);
            }
        }

        ServiceAccountWithKeyDTO result = new ServiceAccountWithKeyDTO();
        result.setId(sa.getId());
        result.setName(sa.getName());
        result.setPrefix(sa.getPrefix());
        result.setKey(rawKey); // shown ONCE
        result.setCreatedAt(sa.getCreatedAt());
        return result;
    }

    @Override
    public List<ServiceAccountDTO> listServiceAccounts(@PathVariable String key) {
        requireManageKeys(key);
        ProcessEntity process = resolveProcess(key);
        return serviceAccountRepository.findByProcessId(process.getId()).stream()
            .map(sa -> {
                ServiceAccountDTO dto = new ServiceAccountDTO();
                dto.setId(sa.getId());
                dto.setName(sa.getName());
                dto.setPrefix(sa.getPrefix());
                dto.setCreatedAt(sa.getCreatedAt());
                dto.setLastUsedAt(sa.getLastUsedAt());
                dto.setExpiresAt(sa.getExpiresAt());
                dto.setRevokedAt(sa.getRevokedAt());
                List<String> perms = saPermissionRepository.findByServiceAccountId(sa.getId()).stream()
                    .map(ServiceAccountPermissionEntity::getPermission)
                    .collect(Collectors.toList());
                dto.setPermissions(perms);
                return dto;
            })
            .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public ServiceAccountWithKeyDTO rotateServiceAccountKey(@PathVariable String key, @PathVariable UUID id) {
        requireManageKeys(key);
        ServiceAccountEntity sa = serviceAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service account not found"));

        String rawKey = generateKey();
        sa.setKeyHash(KeyHasher.sha256(rawKey));
        sa.setPrefix(rawKey.substring(0, Math.min(16, rawKey.length())));
        serviceAccountRepository.save(sa);

        ServiceAccountWithKeyDTO result = new ServiceAccountWithKeyDTO();
        result.setId(sa.getId());
        result.setName(sa.getName());
        result.setPrefix(sa.getPrefix());
        result.setKey(rawKey);
        return result;
    }

    @Transactional
    @Override
    public IdDTO revokeServiceAccount(@PathVariable String key, @PathVariable UUID id) {
        requireManageKeys(key);
        ServiceAccountEntity sa = serviceAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service account not found"));

        sa.setRevokedAt(Instant.now());
        serviceAccountRepository.save(sa);

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }
}
