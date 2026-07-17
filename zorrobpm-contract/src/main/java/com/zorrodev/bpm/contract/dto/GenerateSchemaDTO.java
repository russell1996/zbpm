package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GenerateSchemaDTO {
    private List<FieldDTO> fields;
}
