package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveElementSchemaDTO {
    @NotBlank(message = "Schema kind is required")
    private String kind;

    @NotBlank(message = "Schema body is required")
    private String schema;
}
