package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SchemaMapDTO {
    private String processDefinitionKey;
    private int version;
    private List<SchemaMapElementDTO> elements;
}
