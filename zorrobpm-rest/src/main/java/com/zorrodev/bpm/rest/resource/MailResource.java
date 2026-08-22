package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MailContract;
import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.MailProperties;
import com.zorrodev.bpm.engine.mail.MailStatus;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * WO-INT-5 criteria 7, 8, 9: mail health and test send endpoints.
 * All endpoints require SUPER_ADMIN.
 * <p>
 * Criterion 8 (CTO HOLD round 3): the test-send endpoint performs a SYNCHRONOUS
 * SMTP delivery and only reports success after {@code JavaMailSender.send} has
 * returned — never after merely enqueuing. The body comes from the template
 * {@code mail/test-email.txt} with placeholders, not from string concatenation.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MailResource implements MailContract {

    private final MailHealthService mailHealthService;
    private final MailProperties mailProperties;
    private final MailStatus mailStatus;
    private final HttpServletRequest request;

    /** Present in production profiles; absent under the test profile (no spring.mail.host). */
    @Lazy
    @Autowired(required = false)
    JavaMailSender javaMailSender;

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

        if (javaMailSender == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Mail transport is not available in this profile"
            );
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(mailProperties.from());
            helper.setTo(recipientAddress);
            helper.setSubject("ZorroBPM — Test Email");
            helper.setText(renderTestEmailBody(recipientAddress), false);

            // Real SMTP round-trip: success means the server accepted the message.
            javaMailSender.send(message);
            mailStatus.recordSuccess();
            log.info("Test email sent to {}", recipientAddress);
            return "Test email sent successfully to " + recipientAddress;
        } catch (Exception e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            mailStatus.recordError(cause);
            log.error("Failed to send test email to {}: {}", recipientAddress, cause);
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "SMTP error: " + cause
            );
        }
    }

    /** Loads mail/test-email.txt and substitutes ${recipient}/${timestamp}; no concatenation of prose. */
    private String renderTestEmailBody(String recipient) {
        try (var is = new ClassPathResource("mail/test-email.txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8)
                .replace("${recipient}", recipient)
                .replace("${timestamp}", Instant.now().toString());
        } catch (Exception e) {
            throw new IllegalStateException("Mail template mail/test-email.txt is missing", e);
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
