package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A UI account: username + hashed password + role, used to sign in to the web console. */
@Getter
@Setter
@Entity
@Table(name = "ui_users")
public class UiUserEntity {
    @Id
    private UUID id;
    private String username;
    private String passwordHash;
    private String fullName;
    private String email;
    /** "SUPER_ADMIN", "ADMIN" or "USER". */
    private String role;
    private boolean active;
    private boolean forcePasswordChange;
    /** WO-INT-4: "HUMAN" or "SYSTEM". A SYSTEM account has no usable password. */
    private String userType = "HUMAN";
    private Instant createdAt;
    private Instant updatedAt;
    /**
     * WO-REG-2: self-registration state — PENDING_EMAIL_VERIFICATION, PENDING_APPROVAL,
     * ACTIVE or REJECTED. Java default ACTIVE (like userType above): Hibernate sends
     * explicit NULLs, so the DB DEFAULT alone would NOT save inserts — every
     * pre-registration path (admin create, bootstrap) stays active with zero changes.
     * The Java layer owns the value set (no DB enum, like role/user_type).
     */
    private String registrationStatus = "ACTIVE";
    /** WO-REG-2: when email ownership was confirmed via the one-time link (null = not yet). */
    private Instant emailVerifiedAt;
    /** WO-REG-2: when a live SUPER_ADMIN approved the registration (null = undecided). */
    private Instant approvedAt;
    /** WO-REG-2: approving SUPER_ADMIN (ui_users.id), visible on the user for audit. */
    private UUID approvedBy;
    /** WO-REG-2: when a SUPER_ADMIN rejected the registration (null = not rejected). */
    private Instant rejectedAt;
    /** WO-REG-2: free-text reject reason, internal — never sent to the user in email. */
    private String rejectedReason;
    /**
     * WO-SEC-63: JWT access-token version — incremented on logout and password change.
     * JwtAuthFilter compares claim 'ver' against the current value; mismatch = token revoked.
     * Primitive int: Hibernate sends 0 (matching DB DEFAULT) for existing rows — no migration race.
     */
    private int tokenVersion;
}
