package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MailContract;
import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.SmtpMailSender;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * WO-INT-5 criteria 7, 8, 9: mail health and test send endpoints.
 * All endpoints require SUPER_ADMIN.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MailResource implements MailContract {

    private final MailHealthService mailHealthService;
    @Lazy
    @Autowired(required = false)
    private SmtpMailSender smtpMailSender;
    private final HttpServletRequest request;

    @Override
    public MailHealthDTO getMailHealth() {
        requireSuperAdmin();
        return mailHealthService.getHealth();
    }

    @Override
    public String sendTestMail(@RequestBody String recipientAddress) {
        requireSuperAdmin();
        if (recipientAddress == null || recipientAddress.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient address is required");
        }

        if (smtpMailSender == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Mail transport is not available in this profile"
            );
        }

        try {
            smtpMailSender.send(
                recipientAddress,
                "ZorroBPM — Test Email",
                "This is a test email from ZorroBPM. If you received this, mail transport is working correctly."
            );
            log.info("Test email sent to {}", recipientAddress);
            return "Test email sent successfully to " + recipientAddress;
        } catch (Exception e) {
            log.error("Failed to send test email to {}: {}", recipientAddress, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to send test email: " + e.getMessage()
            );
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mail management requires SUPER_ADMIN");
        }
    }
}
