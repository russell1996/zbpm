package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FieldDTO {
    private String key;
    private String label;
    private String type;
    private Boolean required;
    private List<String> enumValues;
    private Integer min;
    private Integer max;
    private Integer maxLength;
    private String pattern;
    private String itemsType;
    private Integer minItems;
    private Integer maxItems;
}
