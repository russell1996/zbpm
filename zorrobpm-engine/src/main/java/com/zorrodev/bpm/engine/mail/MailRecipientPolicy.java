package com.zorrodev.bpm.engine.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WO-INT-5 criteria 10-11: recipient allow-list for outbound mail.
 * <p>
 * Shared by the production sender ({@link SmtpMailSender}) and the test-send endpoint
 * ({@code MailResource}) so both enforce the same policy — the test-send path must not
 * bypass the recipient filter (CTO HOLD round 5 / P-66).
 */
@Service
public class MailRecipientPolicy {

    private final String allowedRecipientsRaw;

    public MailRecipientPolicy(@Value("${zorrobpm.mail.allowed-recipients:}") String allowedRecipientsRaw) {
        this.allowedRecipientsRaw = allowedRecipientsRaw;
    }

    /**
     * Criterion 11: an empty allow-list means no restriction.
     * Criterion 10: when set, only listed recipients are accepted.
     */
    public boolean isAllowed(String to) {
        if (allowedRecipientsRaw == null || allowedRecipientsRaw.isBlank()) {
            return true;
        }
        Set<String> allowed = Arrays.stream(allowedRecipientsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
        return allowed.contains(to);
    }
}
