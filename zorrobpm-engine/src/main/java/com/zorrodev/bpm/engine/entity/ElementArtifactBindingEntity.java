package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
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
@Table(name = "element_artifact_binding")
public class ElementArtifactBindingEntity {
    @Id
    private UUID id;
    private UUID processDefinitionId;
    private int processDefinitionVersion;
    private String elementId;
    private String artifactKey;
    private int artifactVersion;
    private Instant createdAt;
}
