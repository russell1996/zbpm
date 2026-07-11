package com.zorrodev.bpm.engine.security;

import java.util.Set;
import java.util.UUID;

public sealed interface Principal {
    record UserPrincipal(UUID userId, String username, String globalRole) implements Principal {}
    record ServicePrincipal(UUID serviceAccountId, UUID processId, Set<String> permissions) implements Principal {}

    default boolean isSuperAdmin() {
        return this instanceof UserPrincipal u && "SUPER_ADMIN".equals(u.globalRole());
    }
}
