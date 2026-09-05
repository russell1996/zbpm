package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class DeploymentDTO {
    private UUID id;
    private Instant createdAt;
    private List<DeployedProcessDTO> processes;
    private List<DeployedDecisionDTO> decisions;
}
