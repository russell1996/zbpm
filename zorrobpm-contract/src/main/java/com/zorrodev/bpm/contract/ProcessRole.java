package com.zorrodev.bpm.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonCreator.Mode;

/**
 * Process-scoped membership roles (ADR-8 п.4).
 *
 * <p>This is the ONLY set of roles assignable on a process. The global role
 * ({@code SUPER_ADMIN}) is deliberately NOT part of this enum — it lives on the
 * user account and cannot be issued through the member-management path by
 * construction (the DTO field type excludes it, so deserialization rejects it).
 *
 * <p>Deserialization is strict: an unknown value (typo like {@code "Owner"}, or a
 * global role name) resolves to {@code null}, and the caller rejects it with 400
 * listing the valid roles. No silent fallback to a default role — that is exactly
 * how a typo used to create a rightless member.
 */
public enum ProcessRole {
    OWNER, DESIGNER, VIEWER;

    /**
     * Strict parse used both by JSON deserialization and by DB-role resolution in
     * authz. Unknown or null → {@code null} (authz default is DENY, G-L).
     */
    @JsonCreator(mode = Mode.DELEGATING)
    public static ProcessRole fromName(String name) {
        if (name == null) return null;
        for (ProcessRole role : values()) {
            if (role.name().equals(name)) return role;
        }
        return null;
    }

    /** Comma-separated list of valid roles, for 400 messages. */
    public static String validRolesDescription() {
        return "OWNER, DESIGNER, VIEWER";
    }
}
