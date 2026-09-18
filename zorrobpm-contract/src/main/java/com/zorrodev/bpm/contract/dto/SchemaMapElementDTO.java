package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SchemaMapElementDTO {
    private String elementId;
    private String name;
    private String type;
    private String artifactKey;
    private String kind;
    private Integer artifactVersion;
    private boolean hasExternalReference;
    private boolean shared;
}
