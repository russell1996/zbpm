package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeploymentItemDTO {
    /** Resource kind (BPMN process or DMN decision file). */
    private DeploymentResourceType type;
    /** Resource XML text. */
    private String content;
}
