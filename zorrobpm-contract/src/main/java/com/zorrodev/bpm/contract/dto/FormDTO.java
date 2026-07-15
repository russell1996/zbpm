package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FormDTO {
    private String key;
    private int version;
    private String kind;
    private String schema;
}
