package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class AuditLogEntry {
    private UUID id;
    private String principalType;
    private String principalId;
    private UUID ownerUserId;
    private String action;
    private String processKey;
    private String targetId;
    private Instant at;
}
