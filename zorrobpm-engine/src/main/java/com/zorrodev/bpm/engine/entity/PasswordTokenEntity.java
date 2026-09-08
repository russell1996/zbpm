package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-ACL-18: one-time password-setting links (account invitation AND admin/self password reset).
 * Both share the same lifecycle — a single raw token, stored only as its SHA-256 hash, that can be
 * consumed exactly once before it expires. The token type distinguishes the two use cases but the
 * consume logic is identical ("один механизм одноразовых ссылок").
 */
@Getter
@Setter
@Entity
@Table(name = "password_tokens")
public class PasswordTokenEntity {
    @Id
    private UUID id;
    private UUID userId;
    /** "INVITE", "RESET" or "EMAIL_VERIFY" (WO-REG-2: email-ownership proof, no password inside). */
    private String type;
    /** SHA-256 (base64) of the raw token — the raw value is never persisted (criterion 8). */
    private String tokenHash;
    /** Email the link was sent to (used for re-invite invalidation targeting). */
    private String email;
    private Instant expiresAt;
    private boolean used;
    private Instant consumedAt;
    private Instant createdAt;
}
