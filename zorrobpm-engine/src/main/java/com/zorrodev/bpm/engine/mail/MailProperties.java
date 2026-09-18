package com.zorrodev.bpm.engine.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WO-INT-5: mail configuration bound to {@code zorrobpm.mail.*}.
 * All values come from environment variables — nothing is hardcoded.
 */
@ConfigurationProperties(prefix = "zorrobpm.mail")
public record MailProperties(
    /** SMTP host (env: ZORROBPM_MAIL_HOST). */
    String host,
    /** SMTP port (env: ZORROBPM_MAIL_PORT). */
    Integer port,
    /** SMTP username (env: ZORROBPM_MAIL_USERNAME). */
    String username,
    /** SMTP password (env: ZORROBPM_MAIL_PASSWORD). */
    String password,
    /** Sender address (env: ZORROBPM_MAIL_FROM). */
    String from,
    /** Comma-separated allowed recipients; empty = no restriction (env: ZORROBPM_MAIL_ALLOWED_RECIPIENTS). */
    String allowedRecipients
) {
    /**
     * Returns true if mail is fully configured (all required properties present).
     */
    public boolean isConfigured() {
        return host != null && !host.isBlank()
            && port != null
            && from != null && !from.isBlank();
    }
}
