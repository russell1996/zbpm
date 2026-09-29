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
    /** WO-ACL-7 пункт 4: display name for the member list. */
    private String fullName;
    /** WO-ACL-7 пункт 4: contact for the member list (ADR-8: visible to any authenticated user — accepted by the product owner). */
    private String email;
    private String role;
    private UUID addedBy;
    private Instant addedAt;
    private String processKey;
    /** WO-INT-4: true when the member is a system account (integration, not a person). */
    private Boolean isSystem;
}
