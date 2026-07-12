package com.zorrodev.bpm.engine.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public sealed interface Principal {
    record UserPrincipal(UUID userId, String username, String globalRole) implements Principal {}

    /**
     * ADR-2: one key per user. Grants map: processId → {permissions, isFull}.
     * Replaces old per-process ServicePrincipal.
     */
    record ServicePrincipal(UUID apiKeyId, UUID ownerUserId, Map<UUID, Grant> grants) implements Principal {}

    /** A single grant for one process. */
    record Grant(Set<String> permissions, boolean isFull) {}

    default boolean isSuperAdmin() {
        return this instanceof UserPrincipal u && "SUPER_ADMIN".equals(u.globalRole());
    }
}
