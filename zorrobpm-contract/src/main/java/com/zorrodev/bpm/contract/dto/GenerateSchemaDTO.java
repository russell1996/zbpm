package com.zorrodev.bpm.contract.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GenerateSchemaDTO {
    @NotEmpty(message = "fields list is required and must not be empty")
    @Valid
    private List<FieldDTO> fields;
}
