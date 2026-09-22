package com.zorrodev.bpm.contract.dto;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeployBatchDTO {
    /** Caller-supplied batch description (nullable). */
    private String description;
    /**
     * Resources to lay down atomically, in any order (service orders them: BPMN, then DMN).
     * WO-API-4: {@code @Valid} cascades Bean Validation into each list element —
     * without it the {@code @Size}/{@code @NotBlank} on {@code DeploymentItemDTO.content}
     * never fires and the batch path bypasses the WO-SEC-62 size cap.
     */
    @Valid
    private List<DeploymentItemDTO> resources;
}
