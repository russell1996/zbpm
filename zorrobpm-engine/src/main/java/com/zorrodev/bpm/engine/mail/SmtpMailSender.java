package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.MailSender;
import com.zorrodev.bpm.exchange.MailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-INT-5: production mail sender. Writes a {@link MailRequest} to the outbox
 * with {@code kind=EMAIL}; the {@link com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor}
 * picks it up asynchronously and the {@link MailDeliveryListener} sends it via JavaMailSender.
 * <p>
 * Recipient filtering (criteria 10-11): if {@code ZORROBPM_MAIL_ALLOWED_RECIPIENTS} is set,
 * only listed recipients are accepted. The check happens here (before outbox write) so that
 * blocked emails never consume an outbox slot.
 * <p>
 * Fail-fast is OFF: if mail is not configured or SMTP is down, the entry is still created
 * and will fail at delivery time (criterion 4).
 */
@Slf4j
@Profile("!test")
@Service
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MailRecipientPolicy recipientPolicy;

    @Value("${zorrobpm.mail.from:}")
    private String defaultFrom;

    @Override
    public void send(String to, String subject, String body) {
        send0(to, subject, body, false);
    }

    @Override
    public void sendHtml(String to, String subject, String body) {
        send0(to, subject, body, true);
    }

    @Transactional
    void send0(String to, String subject, String body, boolean html) {
        if (!recipientPolicy.isAllowed(to)) {
            log.warn("WO-INT-5: recipient {} not in allowed list, skipping mail to='{}' subject='{}'",
                to, to, subject);
            return;
        }

        MailRequest request = new MailRequest(to, subject, body, html);

        try {
            OutboxEntry entry = new OutboxEntry();
            entry.setId(UUID.randomUUID());
            entry.setKind(OutboxKind.EMAIL);
            entry.setPayload(objectMapper.writeValueAsString(request));
            entry.setCreatedAt(Instant.now());
            entry.setPublished(false);
            outboxRepository.save(entry);
            log.info("Mail enqueued to outbox: id={} to='{}' subject='{}'", entry.getId(), to, subject);
        } catch (Exception e) {
            log.error("Failed to enqueue mail to='{}' subject='{}'", to, subject, e);
            throw new RuntimeException("Failed to enqueue mail", e);
        }
    }

}
