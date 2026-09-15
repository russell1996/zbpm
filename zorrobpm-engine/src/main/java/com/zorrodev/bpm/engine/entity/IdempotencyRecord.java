package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * WO-REL-21/32: saved response for {@code Idempotency-Key} replay.
 * Identity = (client key, endpoint, actor_id). WO-REL-32 F05: actor_id —
 * стабильный субъект (userId/ownerUserId), не hash токена; ротация не плодит
 * дубли. Credential hash оставлен как deprecated-колонка, не PK.
 */
@Getter
@Setter
@Entity
@IdClass(IdempotencyRecordId.class)
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "idem_key")
    private String idemKey;

    @Id
    private String endpoint;

    @Id
    @Column(name = "actor_id", nullable = false)
    private String actorId;

    /** DEPRECATED с 109: оставлен для совместимости, не PK. */
    @Column(name = "credential_hash", nullable = false)
    private String credentialHash;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "response_content_type")
    private String responseContentType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
