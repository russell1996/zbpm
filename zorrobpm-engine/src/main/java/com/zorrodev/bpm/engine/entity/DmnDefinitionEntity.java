package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A deployed DMN decision: its XML stored by decision id (latest deployment wins). */
@Getter
@Setter
@Entity
@Table(name = "dmn_definitions")
public class DmnDefinitionEntity {
    @Id
    private String decisionId;
    @Lob
    private String dmn;
    private Instant createdAt;
}
