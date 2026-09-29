package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@Entity
@IdClass(ApiKeyGrantEntity.ApiKeyGrantId.class)
@Table(name = "api_key_grant")
public class ApiKeyGrantEntity {

    @Id
    private UUID apiKeyId;
    @Id
    private UUID processId;

    /** Comma-separated permissions or null when isFull=true. */
    @Column(name = "permissions")
    private String permissions;

    @Column(name = "is_full")
    private boolean isFull;

    public record ApiKeyGrantId(UUID apiKeyId, UUID processId) implements Serializable {}
}
