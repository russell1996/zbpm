package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * WO-FORM-5: Server-side validation of submitted variables against form-js schema.
 * Supports: required, minLength, maxLength, pattern (for textfield/textarea),
 * numeric parsing (for number/integer), boolean parsing (for checkbox).
 * Unknown component types are skipped (passthrough).
 */
@Slf4j
@Component
public class FormValidator {

    private final ObjectMapper objectMapper;

    public FormValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ValidationError(String field, String message) {}

    /**
     * Validate submitted variables against a form-js schema.
     * Returns empty list if valid, list of errors otherwise.
     */
    @SuppressWarnings("unchecked")
    public List<ValidationError> validate(String schemaJson, List<ProcessVariable> variables) {
        List<ValidationError> errors = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank() || variables == null) return errors;

        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            List<Map<String, Object>> components = (List<Map<String, Object>>) schema.get("components");
            if (components == null) return errors;

            // Build a map of submitted variables by name
            Map<String, String> submittedVars = new LinkedHashMap<>();
            for (ProcessVariable v : variables) {
                if (v.getName() != null && v.getValue() != null) {
                    submittedVars.put(v.getName(), v.getValue());
                }
            }

            for (Map<String, Object> component : components) {
                String key = (String) component.get("key");
                if (key == null) continue;

                String type = (String) component.get("type");
                String value = submittedVars.get(key);
                Map<String, Object> validate = (Map<String, Object>) component.get("validate");

                // Required check
                if (validate != null && Boolean.TRUE.equals(validate.get("required"))) {
                    if (value == null || value.isBlank()) {
                        errors.add(new ValidationError(key, key + " is required"));
                        continue;
                    }
                }

                if (value == null || value.isBlank()) continue;

                // Type-specific validation
                if ("number".equals(type) || "integer".equals(type)) {
                    try {
                        Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        errors.add(new ValidationError(key, key + " must be a number"));
                        continue;
                    }
                } else if ("checkbox".equals(type) || "boolean".equals(type)) {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        errors.add(new ValidationError(key, key + " must be a boolean"));
                        continue;
                    }
                }
                // textfield, textarea, select, etc. — string validation below

                // String validations (for non-numeric, non-boolean types)
                if (validate != null) {
                    Integer minLength = (Integer) validate.get("minLength");
                    Integer maxLength = (Integer) validate.get("maxLength");
                    String pattern = (String) validate.get("pattern");

                    if (minLength != null && value.length() < minLength) {
                        errors.add(new ValidationError(key, key + " must be at least " + minLength + " characters"));
                    }
                    if (maxLength != null && value.length() > maxLength) {
                        errors.add(new ValidationError(key, key + " must be at most " + maxLength + " characters"));
                    }
                    if (pattern != null && !value.matches(pattern)) {
                        errors.add(new ValidationError(key, key + " does not match the required pattern"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse form schema for validation: {}", e.getMessage());
        }

        return errors;
    }
}
