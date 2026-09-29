package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /process-submissions (WO-ACL-3): the raw BPMN XML of the process
 * the user wants to have deployed. Everything else (key, name, submitter) is
 * derived by the engine from the XML and the authenticated principal.
 */
@Getter
@Setter
public class SubmitProcessSubmissionDTO {
    @NotBlank(message = "bpmn is required")
    private String bpmn;
}