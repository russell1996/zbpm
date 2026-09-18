package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ElementBindingDTO {
    private UUID id;
    private String elementId;
    private String artifactKey;
    private int artifactVersion;
    private UUID processDefinitionId;
    private int processDefinitionVersion;
}
