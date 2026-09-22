package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeploymentItemDTO {
    /** Resource kind (BPMN process or DMN decision file). */
    private DeploymentResourceType type;
    /**
     * Resource XML text.
     * WO-API-4: uniform 5M cap reusing {@code AddProcessDefinitionDTO.MAX_BPMN_LENGTH}
     * (same package, no literal duplication) — closes the batch-path bypass of the
     * WO-SEC-62 single-deploy cap. Uniform on DMN content too (no service-level DMN
     * cap exists at all): first limit there ever was, accepted by CTO. {@code @NotBlank}
     * makes empty content a 400 VALIDATION_ERROR via the same MVC cascade ({@code @Size}
     * alone passes null/"", and the service-level empty check stays for non-MVC callers).
     */
    @NotBlank(message = "Content must not be blank")
    @Size(max = AddProcessDefinitionDTO.MAX_BPMN_LENGTH, message = "Content must not exceed 5 MB")
    private String content;
}
