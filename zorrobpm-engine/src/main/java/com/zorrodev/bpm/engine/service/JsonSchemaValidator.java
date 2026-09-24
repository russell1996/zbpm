package com.zorrodev.bpm.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.DisallowSchemaLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * WO-VM-5: Validates JSON Schema payloads for VARIABLE_SCHEMA artifacts.
 * Uses networknt json-schema-validator for Draft 2020-12 validation.
 *
 * <p>WO-SEC-77 (S-4): explicit no-remote-$ref policy. VARIABLE_SCHEMA
 * artifacts are self-contained — the project has no use case for remote
 * refs, so only pure local JSON pointers ({@code #...}) are allowed. Two
 * layers: (1) a structural pre-scan rejects any {@code $ref} /
 * {@code $recursiveRef} / {@code $dynamicRef} that is not a local pointer
 * BEFORE the factory runs, so no network request can be triggered at all;
 * (2) the factory itself is built with {@link DisallowSchemaLoader} as
 * defense-in-depth. An allowlist of known remote schemas was considered and
 * rejected — there is nothing to allowlist, the artifact store holds the
 * schemas itself.
 */
@Slf4j
@Component
public class JsonSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012,
        builder -> builder.schemaLoaders(loaders -> loaders.add(DisallowSchemaLoader.getInstance())));

    /**
     * Validate that the given JSON string is a parseable JSON Schema.
     * Returns empty set if valid, set of error messages otherwise.
     */
    public Set<String> validateSchema(String jsonSchema) {
        try {
            JsonNode tree = MAPPER.readTree(jsonSchema);
            String remoteRef = findFirstRemoteRef(tree);
            if (remoteRef != null) {
                return Set.of("Invalid JSON Schema: remote $ref is not allowed: " + remoteRef);
            }
            // getSchema parses and validates the schema structure itself
            // If the schema is malformed, this throws
            FACTORY.getSchema(tree);
            return Set.of();
        } catch (Exception e) {
            log.warn("Invalid JSON Schema: {}", e.getMessage());
            return Set.of("Invalid JSON Schema: " + e.getMessage());
        }
    }

    /**
     * Walk the whole schema tree; return the first ref value that is not a
     * pure local pointer, or {@code null} if all refs are local. Covers
     * {@code $ref}, {@code $recursiveRef} and {@code $dynamicRef} at any
     * depth ({@code $defs}, {@code properties}, {@code items}, ...). A bare
     * {@code #} (whole-document ref) is local and allowed.
     */
    private static String findFirstRemoteRef(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (("$ref".equals(name) || "$recursiveRef".equals(name) || "$dynamicRef".equals(name))
                    && value.isTextual()
                    && !value.textValue().startsWith("#")) {
                    return value.textValue();
                }
                String nested = findFirstRemoteRef(value);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                String nested = findFirstRemoteRef(element);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
