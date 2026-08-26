package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * WO-INT-5 criteria 7, 8, 9: mail health and test send endpoints.
 * Admin-only — managed by {@code MailResource}.
 */
public interface MailContract {

    /**
     * Criterion 7, 9: returns current mail configuration and delivery status.
     * Shows: configured (all required properties present), lastSuccess, lastError.
     */
    @GetExchange("/admin/mail/health")
    MailHealthDTO getMailHealth();

    /**
     * Criterion 8: sends a test email to the caller's address.
     * Returns success or SMTP error details.
     */
    @PostExchange("/admin/mail/test")
    String sendTestMail(@RequestBody String recipientAddress);
}
