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
 * WO-REL-21: saved response of a create-mutation for {@code Idempotency-Key} replay.
 * Identity = (client key, endpoint). No principal column by design (see WO-REL-21
 * report, threat-model: replaying someone else's key+body gives nothing beyond a
 * plain HTTP replay of the same request, which needs the same knowledge).
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
