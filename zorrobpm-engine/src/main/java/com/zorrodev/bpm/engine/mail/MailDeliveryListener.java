package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailSendRequested;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * WO-INT-5 / WO-INT-6: sends the actual email when the outbox batch processor picks up an EMAIL entry.
 * Uses a plain {@link EventListener} so the send (and the subsequent OutboxDeliveryResult) happens
 * synchronously inside the batch processor's transaction — the outbox row is already persisted by
 * {@code SmtpMailSender} (separate committed transaction), so this is safe and guarantees the
 * attempt/quarantine counter in {@code OutboxDeliveryResultListener} actually runs.
 * On success publishes OutboxDeliveryResult(acked=true), on failure OutboxDeliveryResult(acked=false).
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
public class MailDeliveryListener {

    private final MailConfigResolver configResolver;
    private final MailTransportFactory transportFactory;
    private final ApplicationEventPublisher publisher;
    private final MailStatus mailStatus;
    private final SmtpCircuitBreaker circuitBreaker;

    /**
     * WO-REL-33: тестовый конструктор без breaker'а — эквивалент закрытой цепи
     * (старые тесты конструируют listener вручную; прод идёт через Spring и
     * получает breaker autowire'ом). Сохраняет совместимость без правки ожиданий.
     */
    public MailDeliveryListener(MailConfigResolver configResolver,
            MailTransportFactory transportFactory,
            ApplicationEventPublisher publisher,
            MailStatus mailStatus) {
        this(configResolver, transportFactory, publisher, mailStatus,
            new SmtpCircuitBreaker(Integer.MAX_VALUE, 60, 1000, 30000));
    }

    /** Прод-конструктор: breaker инжектится Spring'ом. */
    @Autowired
    public MailDeliveryListener(MailConfigResolver configResolver,
            MailTransportFactory transportFactory,
            ApplicationEventPublisher publisher,
            MailStatus mailStatus,
            SmtpCircuitBreaker circuitBreaker) {
        this.configResolver = configResolver;
        this.transportFactory = transportFactory;
        this.publisher = publisher;
        this.mailStatus = mailStatus;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * WO-REL-22 (section A decision): NO dedup here — the same {@code MailSendRequested}
     * delivered twice (redelivery) sends twice, by contract (see {@code MailSender}).
     */
    @EventListener
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
            // WO-REL-33 п.2: весь внешний вызов (build + send) под защитой цепи —
            // недоступный SMTP размыкает её, и следующие попытки отклоняются сразу,
            // не занимая scheduler-поток синхронным 10-секундным таймаутом.
            circuitBreaker.execute(() -> {
                try {
                    JavaMailSender sender = transportFactory.build(
                        cfg.host(), cfg.port(), cfg.username(), cfg.password());
                    MimeMessage message = sender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true);
                    helper.setFrom(cfg.from());
                    helper.setTo(request.getTo());
                    helper.setSubject(request.getSubject());
                    if (request.isHtml()) {
                        helper.setText(request.getBody(), true);
                    } else {
                        helper.setText(request.getBody(), false);
                    }
                    sender.send(message);
                    return null;
                } catch (jakarta.mail.MessagingException e) {
                    throw new RuntimeException(e);
                }
            });
            mailStatus.recordSuccess();
            log.info("Mail sent successfully: outboxId={} to='{}' subject='{}'",
                outboxId, request.getTo(), request.getSubject());

            publisher.publishEvent(new OutboxDeliveryResult(outboxId, true, null));

        } catch (SmtpCircuitOpenException e) {
            // WO-REL-33 п.2: цепь разомкнута — вызов не выполнялся, поток не
            // занимали. Nack как обычно: запись остаётся pending для retry,
            // но сама попытка стоила микросекунды, а не SMTP-таймаут.
            String cause = "SMTP circuit OPEN — server unavailable, attempt skipped";
            mailStatus.recordError(cause);
            log.warn("Mail delivery skipped (circuit open): outboxId={} to='{}' subject='{}'",
                outboxId, request.getTo(), request.getSubject());
            publisher.publishEvent(new OutboxDeliveryResult(outboxId, false, cause));

        } catch (Exception e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            mailStatus.recordError(cause);
            log.error("Mail delivery failed: outboxId={} to='{}' subject='{}' cause='{}'",
                outboxId, request.getTo(), request.getSubject(), cause);
            publisher.publishEvent(new OutboxDeliveryResult(outboxId, false, cause));
        }
    }
}
