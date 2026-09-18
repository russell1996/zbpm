package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A user reference for the "add member" candidate list.
 *
 * WO-ACL-7: originally ONLY {@code userId} and {@code username} — the candidates
 * endpoint must not become a user directory through a side door (the /users
 * directory stays SUPER_ADMIN-only). WO-ACL-15 extends the contract additively
 * with {@code fullName} and {@code email}: the endpoint is MANAGE_MEMBERS-scoped
 * on a concrete process, and MemberDTO already carries the same two fields to
 * every authenticated user since ACL-9. The fields are empty strings, never null,
 * when the account has no name/email (WO-ACL-15 criterion 1).
 */
@Getter
@Setter
public class MemberCandidateDTO {
    private UUID userId;
    private String username;
    private String fullName;
    private String email;
}