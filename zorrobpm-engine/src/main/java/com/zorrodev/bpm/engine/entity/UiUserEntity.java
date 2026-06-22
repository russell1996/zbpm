package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A UI account: username + hashed password + role, used to sign in to the web console. */
@Getter
@Setter
@Entity
@Table(name = "ui_users")
public class UiUserEntity {
    @Id
    private UUID id;
    private String username;
    private String passwordHash;
    private String fullName;
    private String email;
    /** "ADMIN" or "USER". */
    private String role;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
