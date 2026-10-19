package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Body of POST /process-submissions (WO-ACL-3): the raw BPMN XML of the process
 * the user wants to have deployed. Everything else (key, name, submitter) is
 * derived by the engine from the XML and the authenticated principal.
 */
@Getter
@Setter
public class SubmitProcessSubmissionDTO {

    /**
     * WO-AUDIT-13 (F-07): single source of truth for the submission cap (chars, not wire
     * bytes) — 256 KiB, the value the service has always enforced. The constant moved here
     * from the engine so that the DTO cap and the service guard cannot drift apart
     * (the same shape as {@code AddProcessDefinitionDTO.MAX_BPMN_LENGTH} for the deploy path).
     */
    public static final int MAX_BPMN_LENGTH = 262_144;

    @Size(max = MAX_BPMN_LENGTH, message = "BPMN must not exceed 256 KB")
    @NotBlank(message = "bpmn is required")
    private String bpmn;
}
