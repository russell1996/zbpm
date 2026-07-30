package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeployFormDTO {
    @NotBlank(message = "Form key is required")
    private String key;

    @NotBlank(message = "Form kind is required")
    private String kind;

    @NotBlank(message = "Form schema is required")
    private String schema;
}
