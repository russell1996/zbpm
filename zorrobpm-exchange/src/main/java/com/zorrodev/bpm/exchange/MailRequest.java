package com.zorrodev.bpm.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WO-INT-5: mail payload stored in the outbox.
 * The {@code MailSender} implementation serialises this to JSON;
 * the {@code MailDeliveryListener} deserialises it and sends via JavaMailSender.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MailRequest {
    private String to;
    private String subject;
    private String body;
    private boolean html;
}
