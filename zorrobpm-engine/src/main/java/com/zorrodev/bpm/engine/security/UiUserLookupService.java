package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.repository.UiUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * WO-SEC-14: Checks forcePasswordChange flag for a user.
 * Used by ForcePasswordChangeFilter in the rest module.
 * WO-SEC-63: centralizes all per-request user-state lookups (active, role, tokenVersion)
 * into a single projection, used by JwtAuthFilter.
 */
@Service
@RequiredArgsConstructor
public class UiUserLookupService {

    private final UiUserRepository repository;

    public boolean isForcePasswordChange(UUID userId) {
        return repository.findById(userId)
            .map(u -> u.isForcePasswordChange())
            .orElse(false);
    }

    /**
     * WO-ACL-5 criterion #4: is the user still active? A deactivated owner's API key
     * must stop working — checked on every request in JwtAuthFilter.resolveApiKey.
     * Unknown/deleted user → false (DENY, never a silent allow).
     */
    public boolean isActive(UUID userId) {
        return repository.findById(userId)
            .map(u -> u.isActive())
            .orElse(false);
    }

    /**
     * WO-SEC-63: projection of security-relevant fields for JWT authz check.
     * Called once per protected request in JwtAuthFilter (replaces the separate
     * isForcePasswordChange lookup). On prod where forcePasswordEnforce=true,
     * JwtAuthFilter already performed this indexed PK lookup per-request — this
     * merges version/active/role into the same call, eliminating a second DB
     * round-trip on non-exempt paths and adding zero new queries on the existing
     * prod hot path (one findById per request). For non-JWT (API-key) path: not called.
     *
     * @return populated state if user exists, empty if deleted/unknown
     */
    public Optional<UserSecurityState> securityState(UUID userId) {
        return repository.findById(userId)
            .map(u -> new UserSecurityState(
                u.getId(),
                u.getUsername(),
                u.getRole(),
                u.isActive(),
                u.getTokenVersion(),
                u.isForcePasswordChange()));
    }

    /**
     * WO-SEC-63: immutable projection of the user's security-relevant state.
     * Kept as a simple record (not entity) to prevent accidental persistence-context mutation.
     */
    public record UserSecurityState(
        UUID userId,
        String username,
        String role,
        boolean active,
        int tokenVersion,
        boolean forcePasswordChange) {}
}
