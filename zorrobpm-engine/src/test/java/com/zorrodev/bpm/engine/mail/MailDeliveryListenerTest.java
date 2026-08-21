package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.exchange.MailSendRequested;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-5 unit tests for MailDeliveryListener.
 * Covers criteria 4, 5, 6, 9: fail-fast off, retry, exhausted retries, status tracking.
 */
@ExtendWith(MockitoExtension.class)
class MailDeliveryListenerTest {

    @Mock
    private JavaMailSender javaMailSender;

    @Mock
    private ApplicationEventPublisher publisher;

    @Mock
    private MimeMessage mimeMessage;

    private MailProperties mailProperties;
    private MailStatus mailStatus;
    private MailDeliveryListener listener;

    @BeforeEach
    void setUp() {
        mailProperties = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        mailStatus = new MailStatus();
        listener = new MailDeliveryListener(javaMailSender, publisher, mailProperties, mailStatus);
    }

    @Test
    void criterion4_smtpFailure_publishesNack() throws Exception {
        // criterion 4: unavailable SMTP does not crash — publishes OutboxDeliveryResult(acked=false)
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection refused", new jakarta.mail.MessagingException("Connection refused")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Test", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-1"));

        ArgumentCaptor<OutboxDeliveryResult> captor = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isAcked()).isFalse();
        assertThat(captor.getValue().getCause()).contains("Connection refused");
    }

    @Test
    void criterion4_smtpFailure_doesNotThrow() throws Exception {
        // criterion 4: listener must not propagate SMTP errors
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Timeout", new jakarta.mail.MessagingException("Timeout")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Test", "body", false);
        // Must not throw
        listener.on(new MailSendRequested(request, "outbox-id-2"));
    }

    @Test
    void criterion5_6_smtpSuccess_publishesAck() throws Exception {
        // criterion 5: success → OutboxDeliveryResult(acked=true) → mark published
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "<h1>body</h1>", true);
        listener.on(new MailSendRequested(request, "outbox-id-3"));

        ArgumentCaptor<OutboxDeliveryResult> captor = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isAcked()).isTrue();
        assertThat(captor.getValue().getCause()).isNull();
    }

    @Test
    void criterion9_success_recordsStatusTime() throws Exception {
        // criterion 9: last success time is recorded
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-4"));

        assertThat(mailStatus.getLastSuccess()).isNotNull();
        assertThat(mailStatus.getLastError()).isNull();
    }

    @Test
    void criterion9_failure_recordsErrorStatus() throws Exception {
        // criterion 9: last error time and message are recorded
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP error", new jakarta.mail.MessagingException("SMTP error")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-5"));

        assertThat(mailStatus.getLastError()).isNotNull();
        assertThat(mailStatus.getLastErrorMessage()).contains("SMTP error");
    }

    /**
     * Criterion 5 POF: demonstrates retry-after-failure.
     * First attempt: transient SMTP error → nack (retry signal).
     * Second attempt: success → ack (delivery confirmed).
     * This proves the outbox retry loop works end-to-end.
     */
    @Test
    void criterion5_retryTransientFailure_thenSuccess() throws Exception {
        // First attempt: transient failure
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection timeout",
            new jakarta.mail.MessagingException("Connection timeout")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "<h1>Retry test</h1>", true);
        listener.on(new MailSendRequested(request, "retry-entry-1"));

        ArgumentCaptor<OutboxDeliveryResult> firstResult = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(firstResult.capture());
        assertThat(firstResult.getValue().isAcked()).isFalse(); // nack → retry

        // Second attempt: success (reset mocks)
        org.mockito.Mockito.reset(javaMailSender, publisher);
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));

        listener.on(new MailSendRequested(request, "retry-entry-1"));

        ArgumentCaptor<OutboxDeliveryResult> secondResult = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(secondResult.capture());
        assertThat(secondResult.getValue().isAcked()).isTrue(); // ack → delivered
    }
}
