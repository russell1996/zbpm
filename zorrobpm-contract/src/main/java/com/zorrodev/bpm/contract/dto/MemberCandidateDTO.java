package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * WO-ACL-7: a minimal user reference for the "add member" candidate list.
 * Deliberately carries ONLY {@code userId} and {@code username} — no email, no full
 * name, no roles: the candidates endpoint must not become a user directory through
 * a side door (the /users directory stays SUPER_ADMIN-only).
 */
@Getter
@Setter
public class MemberCandidateDTO {
    private UUID userId;
    private String username;
}