package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "process")
public class ProcessEntity {
    @Id
    private UUID id;
    private String definitionKey;
    private String name;
    private Instant createdAt;
    private boolean archived;
    private Instant archivedAt;
}
