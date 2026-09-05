package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeployBatchDTO {
    /** Caller-supplied batch description (nullable). */
    private String description;
    /** Resources to lay down atomically, in any order (service orders them: BPMN, then DMN). */
    private List<DeploymentItemDTO> resources;
}
