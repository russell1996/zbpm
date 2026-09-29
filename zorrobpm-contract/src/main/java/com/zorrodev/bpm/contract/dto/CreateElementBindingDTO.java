package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateElementBindingDTO {
    @NotBlank(message = "elementId is required")
    private String elementId;

    @NotBlank(message = "artifactKey is required")
    private String artifactKey;
}
