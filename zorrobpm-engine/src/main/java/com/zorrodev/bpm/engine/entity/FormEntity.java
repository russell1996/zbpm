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
@Table(name = "form")
public class FormEntity {
    @Id
    private UUID id;
    private String formKey;
    private int version;
    @Column(columnDefinition = "text")
    private String schemaJson;
    private Instant createdAt;
}
