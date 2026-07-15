package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateElementBindingDTO {
    private String elementId;
    private String artifactKey;
}
