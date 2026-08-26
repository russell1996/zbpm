package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailSendRequested;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * WO-INT-5 / WO-INT-6: sends the actual email when the outbox batch processor picks up an EMAIL entry.
 * Uses TransactionalEventListener(phase=AFTER_COMMIT) so the email is sent only after the outbox entry
 * is safely persisted. On success publishes OutboxDeliveryResult(acked=true), on failure
 * OutboxDeliveryResult(acked=false).
 * <p>
 * Criterion 4 (fail-fast OFF): when no transport can be resolved (ZORROBPM_MAIL_* unset AND no DB row),
 * the application must still START. The listener resolves the transport lazily via MailConfigResolver;
 * without it the entry is nacked with a clear cause and flows into the normal retry/quarantine path.
 * <p>
 * WO-INT-6 criterion 3 (hot-reload): the effective config is resolved per event, so a saved DB row
 * applies on the next send without a restart.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
public class MailDeliveryListener {

    private final MailConfigResolver configResolver;
    private final MailTransportFactory transportFactory;
    private final ApplicationEventPublisher publisher;
    private final MailStatus mailStatus;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MailSendRequested event) {
        String outboxId = event.getOutboxId();
        var request = event.getRequest();

        ResolvedMailConfig cfg = configResolver.getEffectiveConfig();
        if (cfg == null || cfg.host() == null || cfg.host().isBlank()) {
            String cause = "Mail transport is not configured (no mail_settings row and ZORROBPM_MAIL_HOST missing)";
            mailStatus.recordError(cause);
            log.error("Mail delivery impossible: outboxId={} to='{}' subject='{}' cause='{}'",
                outboxId, request.getTo(), request.getSubject(), cause);
            publisher.publishEvent(new OutboxDeliveryResult(outboxId, false, cause));
            return;
        }

        try {
            JavaMailSender javaMailSender = transportFactory.build(cfg.host(), cfg.port(), cfg.username(), cfg.password());
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(cfg.from());
            helper.setTo(request.getTo());
            helper.setSubject(request.getSubject());
            if (request.isHtml()) {
                helper.setText(request.getBody(), true);
            } else {
                helper.setText(request.getBody(), false);
            }

            javaMailSender.send(message);
            mailStatus.recordSuccess();
            log.info("Mail sent successfully: outboxId={} to='{}' subject='{}'",
                outboxId, request.getTo(), request.getSubject());

            publisher.publishEvent(new OutboxDeliveryResult(outboxId, true, null));

        } catch (Exception e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            mailStatus.recordError(cause);
            log.error("Mail delivery failed: outboxId={} to='{}' subject='{}' cause='{}'",
                outboxId, request.getTo(), request.getSubject(), cause);
            publisher.publishEvent(new OutboxDeliveryResult(outboxId, false, cause));
        }
    }
}
