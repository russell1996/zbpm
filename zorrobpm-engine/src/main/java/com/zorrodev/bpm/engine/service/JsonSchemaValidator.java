package com.zorrodev.bpm.engine.service;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WO-VM-5: Validates JSON Schema payloads for VARIABLE_SCHEMA artifacts.
 * Uses networknt json-schema-validator for Draft 2020-12 validation.
 */
@Slf4j
@Component
public class JsonSchemaValidator {

    /**
     * Validate that the given JSON string is a parseable JSON Schema.
     * Returns empty set if valid, set of error messages otherwise.
     */
    public Set<String> validateSchema(String jsonSchema) {
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            // getSchema parses and validates the schema structure itself
            // If the schema is malformed, this throws
            factory.getSchema(jsonSchema);
            return Set.of();
        } catch (Exception e) {
            log.warn("Invalid JSON Schema: {}", e.getMessage());
            return Set.of("Invalid JSON Schema: " + e.getMessage());
        }
    }
}
