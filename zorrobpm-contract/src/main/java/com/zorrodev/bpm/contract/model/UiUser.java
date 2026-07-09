package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A UI account as returned to the client (never carries the password hash). */
@Getter
@Setter
public class UiUser {
    private UUID id;
    private String username;
    private String fullName;
    private String email;
    private String role;
    private boolean active;
    private boolean forcePasswordChange;
    private Instant createdAt;
    private Instant updatedAt;
}
