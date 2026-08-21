package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WO-INT-5: Spring event published by {@code OutboxBatchProcessor} when it picks up
 * an outbox entry with {@code kind=EMAIL}. The {@code MailDeliveryListener} sends
 * the actual email via JavaMailSender.
 */
@Getter
@RequiredArgsConstructor
public class MailSendRequested {
    private final MailRequest request;
    private final String outboxId;
}
