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
    /**
     * WO-INT-5 criteria 7/9 (CTO HOLD round 3): live SMTP reachability probe result.
     * true = server accepted a connection (bad credentials still count as reachable),
     * false = connection attempt failed, null = no probe possible (transport not configured).
     * Additive field per CTO HOLD instruction; backward compatible.
     */
    private Boolean reachable;
    /** Timestamp of the last successful send, or null if none. */
    private Instant lastSuccess;
    /** Timestamp of the last failed send, or null if none. */
    private Instant lastError;
    /** Error message from the last failed send, or null if none. */
    private String lastErrorMessage;
}
