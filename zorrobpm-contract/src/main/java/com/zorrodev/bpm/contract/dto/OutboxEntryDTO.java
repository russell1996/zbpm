package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-REL-22 (B1/B2): admin view of an outbox row (quarantine list + re-drive).
 */
@Getter
@Setter
public class OutboxEntryDTO {
    private UUID id;
    private String kind;
    private String status;
    private boolean published;
    private int attempts;
    private String lastError;
    private Instant createdAt;
}
