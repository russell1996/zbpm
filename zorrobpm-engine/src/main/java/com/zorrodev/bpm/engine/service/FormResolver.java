package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * WO-FORM-5: Shared form resolution logic (P-14 — used by FormResource + RuntimeResource).
 * Resolves formKey → type/schema/data/url.
 */
@Component
@RequiredArgsConstructor
public class FormResolver {

    private final FormRepository formRepository;

    public TaskFormDTO resolveTaskForm(String formKey, java.util.Map<String, String> prefillData) {
        if (formKey == null || formKey.isBlank()) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("none");
            return dto;
        }

        if (formKey.startsWith("http://") || formKey.startsWith("https://")) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("external");
            dto.setUrl(formKey);
            return dto;
        }

        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(formKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for key: " + formKey));

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    /**
     * WO-C8-22: resolves a Modeler-linked form by its {@code formId} — always the latest
     * deployed version carrying this id (binding {@code latest}; {@code deployment} and
     * {@code versionTag} are separate WOs). A missing form behaves exactly like a missing
     * {@code formKey} above (404, same shape) — never a silent {@code type: "none"}.
     */
    public TaskFormDTO resolveTaskFormByFormId(String formId, java.util.Map<String, String> prefillData) {
        FormEntity form = (formId == null || formId.isBlank()) ? null
            : formRepository.findTopByFormIdOrderByVersionDesc(formId).orElse(null);
        if (form == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for id: " + formId);
        }

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    public String getSchemaJson(String formKey) {
        if (formKey == null || formKey.isBlank()) return null;
        if (formKey.startsWith("http://") || formKey.startsWith("https://")) return null;
        return formRepository.findTopByFormKeyOrderByVersionDesc(formKey)
            .map(FormEntity::getSchemaJson)
            .orElse(null);
    }
}
