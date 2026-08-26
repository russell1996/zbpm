package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

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
        if (schemaJson == null || schemaJson.isBlank()) return errors;

        // WO-FORM-5: null variables → treat as empty list (validate required → errors)
        List<ProcessVariable> vars = variables == null ? List.of() : variables;

        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            List<Map<String, Object>> components = (List<Map<String, Object>>) schema.get("components");
            if (components == null) return errors;

            // Build a map of submitted variables by name
            Map<String, String> submittedVars = new LinkedHashMap<>();
            for (ProcessVariable v : vars) {
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
                    Object minLenObj = validate.get("minLength");
                    Object maxLenObj = validate.get("maxLength");
                    String pattern = (String) validate.get("pattern");

                    if (minLenObj instanceof Number minLength) {
                        if (value.length() < minLength.intValue()) {
                            errors.add(new ValidationError(key, key + " must be at least " + minLength.intValue() + " characters"));
                        }
                    }
                    if (maxLenObj instanceof Number maxLength) {
                        if (value.length() > maxLength.intValue()) {
                            errors.add(new ValidationError(key, key + " must be at most " + maxLength.intValue() + " characters"));
                        }
                    }
                    if (pattern != null && !Pattern.compile(pattern).matcher(value).find()) {
                        errors.add(new ValidationError(key, key + " does not match the required pattern"));
                    }
                }
            }
        } catch (Exception e) {
            // WO-SEC-59 #3: fail-closed — a schema that cannot be parsed must NOT be treated as valid.
            log.warn("Failed to parse form schema for validation: {}", e.getMessage());
            errors.add(new ValidationError("schema", "Form schema could not be parsed: " + e.getMessage()));
        }

        return errors;
    }
}
