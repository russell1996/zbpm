package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * WO-INT-5 criteria 7, 9: mail health status DTO.
 * Returned by GET /admin/mail/health.
 */
@Getter
@Setter
public class MailHealthDTO {
    /** Whether all required mail properties are configured. */
    private boolean configured;
    /** Timestamp of the last successful send, or null if none. */
    private Instant lastSuccess;
    /** Timestamp of the last failed send, or null if none. */
    private Instant lastError;
    /** Error message from the last failed send, or null if none. */
    private String lastErrorMessage;
}
