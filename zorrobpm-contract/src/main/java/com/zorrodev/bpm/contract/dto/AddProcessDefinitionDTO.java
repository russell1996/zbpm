package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddProcessDefinitionDTO {
    /** WO-SEC-62: single source of truth for the BPMN upload cap (chars, not wire bytes). */
    public static final int MAX_BPMN_LENGTH = 5_000_000;

    @Size(max = MAX_BPMN_LENGTH, message = "BPMN must not exceed 5 MB")
    private String bpmn;
}
