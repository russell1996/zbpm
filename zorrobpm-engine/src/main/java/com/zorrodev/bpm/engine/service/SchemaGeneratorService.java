package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.FieldDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SchemaGeneratorService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String generateSchema(List<FieldDTO> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new EngineException("fields list must not be empty");
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        for (FieldDTO field : fields) {
            if (field.getKey() == null || field.getKey().isBlank()) {
                throw new EngineException("field key must not be blank");
            }
            if (field.getType() == null || field.getType().isBlank()) {
                throw new EngineException("field type must not be blank for key: " + field.getKey());
            }

            ObjectNode prop = MAPPER.createObjectNode();
            mapType(field, prop);

            if (Boolean.TRUE.equals(field.getRequired())) {
                required.add(field.getKey());
            }

            properties.set(field.getKey(), prop);
        }

        root.set("properties", properties);
        if (!required.isEmpty()) {
            root.set("required", required);
        }

        // x-builder: round-trip metadata
        ArrayNode xBuilderFields = MAPPER.createArrayNode();
        for (FieldDTO field : fields) {
            ObjectNode fb = MAPPER.createObjectNode();
            if (field.getKey() != null) fb.put("key", field.getKey());
            if (field.getLabel() != null) fb.put("label", field.getLabel());
            if (field.getType() != null) fb.put("type", field.getType());
            if (field.getRequired() != null) fb.put("required", field.getRequired());
            if (field.getEnumValues() != null && !field.getEnumValues().isEmpty()) {
                ArrayNode ev = MAPPER.createArrayNode();
                for (String v : field.getEnumValues()) ev.add(v);
                fb.set("enum", ev);
            }
            if (field.getMin() != null) fb.put("min", field.getMin());
            if (field.getMax() != null) fb.put("max", field.getMax());
            if (field.getMaxLength() != null) fb.put("maxLength", field.getMaxLength());
            if (field.getPattern() != null) fb.put("pattern", field.getPattern());
            if (field.getItemsType() != null) fb.put("itemsType", field.getItemsType());
            if (field.getMinItems() != null) fb.put("minItems", field.getMinItems());
            if (field.getMaxItems() != null) fb.put("maxItems", field.getMaxItems());
            xBuilderFields.add(fb);
        }

        ObjectNode xBuilder = MAPPER.createObjectNode();
        xBuilder.set("fields", xBuilderFields);
        root.set("x-builder", xBuilder);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new EngineException("Failed to serialize generated schema: " + e.getMessage());
        }
    }

    private void mapType(FieldDTO field, ObjectNode prop) {
        switch (field.getType()) {
            case "string" -> {
                prop.put("type", "string");
                if (field.getMaxLength() != null) prop.put("maxLength", field.getMaxLength());
                if (field.getPattern() != null) prop.put("pattern", field.getPattern());
            }
            case "number" -> {
                prop.put("type", "number");
                if (field.getMin() != null) prop.put("minimum", field.getMin());
                if (field.getMax() != null) prop.put("maximum", field.getMax());
            }
            case "integer" -> {
                prop.put("type", "integer");
                if (field.getMin() != null) prop.put("minimum", field.getMin());
                if (field.getMax() != null) prop.put("maximum", field.getMax());
            }
            case "boolean" -> prop.put("type", "boolean");
            case "date" -> {
                prop.put("type", "string");
                prop.put("format", "date");
            }
            case "datetime" -> {
                prop.put("type", "string");
                prop.put("format", "date-time");
            }
            case "array" -> {
                prop.put("type", "array");
                ObjectNode items = MAPPER.createObjectNode();
                items.put("type", field.getItemsType() != null ? field.getItemsType() : "string");
                prop.set("items", items);
                if (field.getMinItems() != null) prop.put("minItems", field.getMinItems());
                if (field.getMaxItems() != null) prop.put("maxItems", field.getMaxItems());
            }
            default -> throw new EngineException("Unknown field type: " + field.getType());
        }

        if (field.getEnumValues() != null && !field.getEnumValues().isEmpty()) {
            ArrayNode enumNode = MAPPER.createArrayNode();
            for (String v : field.getEnumValues()) enumNode.add(v);
            prop.set("enum", enumNode);
        }
    }
}
