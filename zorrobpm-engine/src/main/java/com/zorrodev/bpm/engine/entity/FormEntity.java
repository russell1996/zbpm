package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    /**
     * WO-C8-22: linked-form id (Camunda {@code zeebe:formDefinition/@formId}) — the address
     * key for Modeler-linked forms, filled where known at resource deployment (embedded
     * {@code userTaskForm} id, or the {@code id} inside an uploaded form JSON). Nullable:
     * rows written before this WO (or without an id) resolve by {@code formKey} as before.
     */
    private String formId;
    private int version;
    @Column(columnDefinition = "text")
    private String schemaJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FormArtifactKind kind;
    private Instant createdAt;

    private static final tools.jackson.databind.ObjectMapper FORM_ID_MAPPER = new tools.jackson.databind.ObjectMapper();

    /**
     * WO-C8-22: extracts the linked-form id from a form schema JSON (Camunda form JSON
     * carries its id in the root {@code "id"} property). Returns null when absent,
     * non-textual or unparseable — callers store null (resolve by formKey as before).
     */
    public static String extractFormId(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        try {
            var node = FORM_ID_MAPPER.readTree(schemaJson).get("id");
            return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
