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
 *
 * <p>WO-API-1 (F17): рекурсивный обход вложенных {@code components}
 * (required внутри group/container проверяется); {@code integer} валидируется
 * строго ({@code Long.parseLong} — дробь/NaN/Infinity отклоняются, в отличие от
 * {@code Double.parseDouble}); regex из схемы исполняется с защитой от
 * catastrophic backtracking (см. {@link #matchesPattern}).
 */
@Slf4j
@Component
public class FormValidator {

    /**
     * WO-API-1 (F17, ReDoS): жёсткий лимит длины pattern + значения. form-js
     * patterns — короткие якорные выражения для форматов (email/phone/zip);
     * всё длиннее — не формат, а попытка положить request-поток. Лимит по длине
     * дешёвый, детерминированный и не требует потокобезопасных таймаутов
     * (interrupt-based timeout на общем пуле ненадёжен).
     */
    static final int MAX_PATTERN_LENGTH = 256;
    static final int MAX_PATTERN_VALUE_LENGTH = 4096;

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

            validateComponents(components, submittedVars, errors);
        } catch (Exception e) {
            // WO-SEC-59 #3: fail-closed — a schema that cannot be parsed must NOT be treated as valid.
            log.warn("Failed to parse form schema for validation: {}", e.getMessage());
            errors.add(new ValidationError("schema", "Form schema could not be parsed: " + e.getMessage()));
        }

        return errors;
    }

    /**
     * WO-API-1 (F17): рекурсивный обход — group/container/fieldset несут свои
     * {@code components}, required внутри них обязан проверяться так же, как
     * top-level. Глубина form-js схем — единицы, рекурсия безопасна.
     */
    @SuppressWarnings("unchecked")
    private void validateComponents(List<Map<String, Object>> components,
            Map<String, String> submittedVars, List<ValidationError> errors) {
        for (Map<String, Object> component : components) {
            Object nested = component.get("components");
            if (nested instanceof List<?> nestedList && !nestedList.isEmpty()
                    && nestedList.get(0) instanceof Map) {
                validateComponents((List<Map<String, Object>>) nestedList, submittedVars, errors);
            }

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
            if ("integer".equals(type)) {
                // WO-API-1 (F17): строго целое — Long.parseLong отклоняет дробь,
                // NaN, Infinity, hex, пробелы по краям (trim перед парсингом —
                // form-js шлёт чистые значения, пробелы вокруг числа не число).
                try {
                    Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    errors.add(new ValidationError(key, key + " must be an integer"));
                    continue;
                }
            } else if ("number".equals(type)) {
                try {
                    double parsed = Double.parseDouble(value.trim());
                    // WO-API-1 (F17): NaN/Infinity — не числа для формы
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        errors.add(new ValidationError(key, key + " must be a number"));
                        continue;
                    }
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
                if (pattern != null && !matchesPattern(pattern, value)) {
                    errors.add(new ValidationError(key, key + " does not match the required pattern"));
                }
            }
        }
    }

    /**
     * WO-API-1 (F17, ReDoS): pattern-match с fail-closed лимитами. Длинный pattern
     * или длинное значение отклоняются как невалидные БЕЗ исполнения regex —
     * catastrophic backtracking невозможен конструктивно (движок не запускается).
     * Легитимные form-js patterns (email/phone/zip/code) — десятки символов;
     * значения — единицы KB максимум.
     */
    private boolean matchesPattern(String pattern, String value) {
        if (pattern.length() > MAX_PATTERN_LENGTH || value.length() > MAX_PATTERN_VALUE_LENGTH) {
            log.warn("Form pattern rejected without execution: patternLen={}, valueLen={}",
                pattern.length(), value.length());
            return false;
        }
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (Exception e) {
            // WO-SEC-59 #3, та же fail-closed дисциплина: битый pattern схемы —
            // невалидное значение, не пропуск.
            log.warn("Invalid form pattern rejected: {}", e.getMessage());
            return false;
        }
    }
}
