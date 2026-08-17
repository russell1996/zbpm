package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.repository.UiUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WO-SEC-14: Checks forcePasswordChange flag for a user.
 * Used by ForcePasswordChangeFilter in the rest module.
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
}
