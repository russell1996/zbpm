package com.zorrodev.bpm.engine.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * WO-INT-5 criterion 9: in-memory tracker for mail sending status.
 * Updated by {@link MailDeliveryListener} on each send attempt.
 * Read by {@link MailHealthService} to expose via /admin/mail/health.
 */
@Component
public class MailStatus {

    @Setter
    private volatile boolean configured;

    @Getter
    private volatile Instant lastSuccess;

    @Getter
    private volatile Instant lastError;

    @Getter
    @Setter
    private volatile String lastErrorMessage;

    public void recordSuccess() {
        this.lastSuccess = Instant.now();
        this.lastErrorMessage = null;
    }

    public void recordError(String message) {
        this.lastError = Instant.now();
        this.lastErrorMessage = message;
    }
}
