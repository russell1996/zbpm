package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ApiKeyDTO {
    private UUID id;
    private UUID ownerUserId;
    private String prefix;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private List<ApiKeyGrantDTO> grants;
}
