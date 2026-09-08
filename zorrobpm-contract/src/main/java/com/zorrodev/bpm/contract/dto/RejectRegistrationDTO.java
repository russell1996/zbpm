package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-REG-5: payload for POST /admin/registrations/{id}/reject — optional free-text reason.
 * Internal only; never sent to the applicant in email (criterion 5).
 */
@Getter
@Setter
public class RejectRegistrationDTO {
    private String reason;
}
