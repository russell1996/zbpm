package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ADR-6 §D9: Facade that encapsulates form-js validation.
 * Runtime delegates here; this service validates only if kind=FORM_JS,
 * otherwise no-op. Runtime never references kind/VARIABLE_SCHEMA/FORM_JS.
 */
@Component
@RequiredArgsConstructor
public class FormArtifactService {

    private final FormRepository formRepository;
    private final FormValidator formValidator;

    /**
     * Validate variables against form schema if applicable (kind=FORM_JS).
     * No-op for VARIABLE_SCHEMA or missing form.
     *
     * @param formKey  the form key to resolve
     * @param variables submitted variables
     * @return validation errors (empty if valid or not applicable)
     */
    public List<FormValidator.ValidationError> validateFormIfApplicable(String formKey, List<ProcessVariable> variables) {
        if (formKey == null || formKey.isBlank()) {
            return List.of();
        }

        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(formKey).orElse(null);
        if (form == null) {
            return List.of();
        }

        // ADR-6 §D9: validate ONLY if kind=FORM_JS; VARIABLE_SCHEMA → no-op
        if (form.getKind() != FormArtifactKind.FORM_JS) {
            return List.of();
        }

        return formValidator.validate(form.getSchemaJson(), variables);
    }
}
