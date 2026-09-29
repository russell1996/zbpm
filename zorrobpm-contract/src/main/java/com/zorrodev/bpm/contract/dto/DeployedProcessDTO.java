package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class DeployedProcessDTO {
    private UUID processDefinitionId;
    private String key;
    private Integer version;
}
