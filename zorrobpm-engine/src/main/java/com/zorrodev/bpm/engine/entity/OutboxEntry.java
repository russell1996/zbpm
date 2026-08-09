package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "outbox")
public class OutboxEntry {
    @Id
    private UUID id;
    private String payload;
    private Instant createdAt;
    private boolean published;
    private int attempts;
    @Column(name = "last_error")
    private String lastError;
    private String status = "PENDING";
    /** WO-REL-12 R-01: explicit entry type set by the producer; routing must not guess from payload. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxKind kind = OutboxKind.SERVICE_TASK;
}
