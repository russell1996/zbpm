package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADR-6 §D3: Serves JSON Schema payload for kind=VARIABLE_SCHEMA.
 * Called only by external BFF via resolve endpoints, never from Runtime.
 */
@Component
@RequiredArgsConstructor
public class VariableSchemaService {

    private final FormRepository formRepository;

    /**
     * Get JSON Schema payload for a VARIABLE_SCHEMA artifact.
     *
     * @param formKey the artifact key
     * @return JSON Schema string, or null if not found or not VARIABLE_SCHEMA
     */
    public String getSchemaPayload(String formKey) {
        if (formKey == null || formKey.isBlank()) return null;

        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(formKey).orElse(null);
        if (form == null || form.getKind() != FormArtifactKind.VARIABLE_SCHEMA) {
            return null;
        }
        return form.getSchemaJson();
    }
}
