package com.zorrodev.bpm.engine.service;

/**
 * WO-INT-5: abstraction for sending mail. Swappable — the production implementation
 * writes to the outbox; the test stub captures sent emails in memory.
 */
public interface MailSender {

    /**
     * Send a plain-text email.
     *
     * @param to      recipient address
     * @param subject email subject
     * @param body    plain-text body
     */
    void send(String to, String subject, String body);

    /**
     * Send an HTML email.
     *
     * @param to      recipient address
     * @param subject email subject
     * @param body    HTML body
     */
    void sendHtml(String to, String subject, String body);
}
