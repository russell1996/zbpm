package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /process-submissions/{id}/reject (WO-ACL-3). The reason is mandatory —
 * a rejection without an explanation leaves the submitter guessing why their process
 * was declined.
 */
@Getter
@Setter
public class RejectSubmissionDTO {
    @NotBlank(message = "reason is required")
    private String reason;
}