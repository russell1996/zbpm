package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.VariableSchemaContract;
import com.zorrodev.bpm.contract.dto.FieldDTO;
import com.zorrodev.bpm.contract.dto.GenerateSchemaDTO;
import com.zorrodev.bpm.contract.dto.GeneratedSchemaDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import com.zorrodev.bpm.engine.service.SchemaGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
@Slf4j
public class VariableSchemaResource implements VariableSchemaContract {

    private final SchemaGeneratorService schemaGeneratorService;
    private final JsonSchemaValidator jsonSchemaValidator;

    @Override
    public GeneratedSchemaDTO generateSchema(@Valid @RequestBody GenerateSchemaDTO dto) {
        if (dto.getFields() == null || dto.getFields().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fields must not be empty");
        }

        for (FieldDTO field : dto.getFields()) {
            if (field.getKey() == null || field.getKey().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "field key must not be blank");
            }
            if (field.getType() == null || field.getType().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "field type must not be blank for key: " + field.getKey());
            }
        }

        String schema;
        try {
            schema = schemaGeneratorService.generateSchema(dto.getFields());
        } catch (EngineException e) {
            log.warn("Schema generation error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schema generation failed");
        }

        // Validate the generated schema passes VM-5 validator
        Set<String> errors = jsonSchemaValidator.validateSchema(schema);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Generated schema is invalid: " + String.join("; ", errors));
        }

        GeneratedSchemaDTO result = new GeneratedSchemaDTO();
        result.setSchema(schema);
        return result;
    }
}
