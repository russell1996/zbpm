package com.zorrodev.bpm.engine.mail;

/**
 * Effective SMTP configuration resolved at call time (DB row overrides env).
 */
public record ResolvedMailConfig(
    String host,
    Integer port,
    String username,
    String password,
    String from,
    String allowedRecipients
) {
}
