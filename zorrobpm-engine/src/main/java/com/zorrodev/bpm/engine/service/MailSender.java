package com.zorrodev.bpm.engine.service;

/**
 * WO-INT-5: abstraction for sending mail. Swappable — the production implementation
 * writes to the outbox; the test stub captures sent emails in memory.
 *
 * <p>WO-REL-22 (section A decision): delivery through this interface is explicitly
 * AT-LEAST-ONCE and NOT idempotent — a redelivered message (RabbitMQ NACK, reconnect,
 * consumer restart) sends the letter AGAIN. This is accepted consciously: mail goes
 * out on admin/system triggers (never on a money path), redelivery is rare, and the
 * cost of a duplicate is one extra letter, not a double charge. Building an inbox
 * table ({@code inbox_record} + {@code ON CONFLICT DO NOTHING}) for this was judged
 * overkill. Consumers that need exactly-once must dedupe upstream by message id.
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
