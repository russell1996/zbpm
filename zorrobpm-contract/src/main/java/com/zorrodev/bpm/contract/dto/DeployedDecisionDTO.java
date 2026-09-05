package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeployedDecisionDTO {
    private String decisionId;
    private int version;
}
