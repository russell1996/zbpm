package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id
    private UUID id;
    private String principalType;
    private String principalId;
    private UUID ownerUserId;
    private String action;
    private String processKey;
    private String targetId;
    private Instant at;
    /** External user ID when acting via API key (WO-INT-2). */
    private String onBehalfOf;
}
