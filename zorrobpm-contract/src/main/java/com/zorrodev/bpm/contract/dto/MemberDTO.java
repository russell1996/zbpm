package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class MemberDTO {
    private UUID userId;
    private String username;
    private String role;
    private UUID addedBy;
    private Instant addedAt;
    private String processKey;
}
