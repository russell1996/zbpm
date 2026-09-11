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
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-5 / WO-INT-6 unit tests for MailDeliveryListener.
 * Covers criteria 4, 5, 6, 9: fail-fast off, retry, exhausted retries, status tracking.
 * The listener now resolves the effective config per event (hot-reload, criterion 3) via
 * MailConfigResolver and builds the transport via MailTransportFactory.
 */
@ExtendWith(MockitoExtension.class)
class MailDeliveryListenerTest {

    @Mock JavaMailSenderImpl javaMailSender;
    @Mock ApplicationEventPublisher publisher;
    @Mock MimeMessage mimeMessage;
    @Mock MailConfigResolver configResolver;
    @Mock MailTransportFactory transportFactory;

    private MailStatus mailStatus;
    private MailDeliveryListener listener;

    @BeforeEach
    void setUp() {
        mailStatus = new MailStatus();
        listener = new MailDeliveryListener(configResolver, transportFactory, publisher, mailStatus);
    }

    private void withConfig() {
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        lenient().when(transportFactory.build("smtp.test.com", 587, "user", "pass")).thenReturn(javaMailSender);
    }

    private void withoutConfig() {
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(null);
    }

    @Test
    void criterion4_noTransportConfigured_publishesNack_andDoesNotThrow() {
        withoutConfig();
        MailRequest request = new MailRequest("user@test.com", "Test", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-no-transport"));
        ArgumentCaptor<OutboxDeliveryResult> captor = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isAcked()).isFalse();
        assertThat(captor.getValue().getCause()).contains("not configured");
        assertThat(mailStatus.getLastErrorMessage()).contains("not configured");
        verify(transportFactory, never()).build(any(), any(), any(), any());
    }

    @Test
    void criterion4_smtpFailure_publishesNack() throws Exception {
        withConfig();
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
        withConfig();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Timeout", new jakarta.mail.MessagingException("Timeout")))
            .when(javaMailSender).send(any(MimeMessage.class));
        MailRequest request = new MailRequest("user@test.com", "Test", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-2")); // must not throw
    }

    @Test
    void criterion5_6_smtpSuccess_publishesAck() throws Exception {
        withConfig();
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
        withConfig();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));
        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-4"));
        assertThat(mailStatus.getLastSuccess()).isNotNull();
        assertThat(mailStatus.getLastError()).isNull();
    }

    @Test
    void criterion9_failure_recordsErrorStatus() throws Exception {
        withConfig();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP error", new jakarta.mail.MessagingException("SMTP error")))
            .when(javaMailSender).send(any(MimeMessage.class));
        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        listener.on(new MailSendRequested(request, "outbox-id-5"));
        assertThat(mailStatus.getLastError()).isNotNull();
        assertThat(mailStatus.getLastErrorMessage()).contains("SMTP error");
    }

    @Test
    void criterion5_retryTransientFailure_thenSuccess() throws Exception {
        withConfig();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection timeout",
            new jakarta.mail.MessagingException("Connection timeout")))
            .when(javaMailSender).send(any(MimeMessage.class));
        MailRequest request = new MailRequest("user@test.com", "Subject", "<h1>Retry test</h1>", true);
        listener.on(new MailSendRequested(request, "retry-entry-1"));
        ArgumentCaptor<OutboxDeliveryResult> firstResult = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(firstResult.capture());
        assertThat(firstResult.getValue().isAcked()).isFalse();

        org.mockito.Mockito.reset(javaMailSender, publisher);
        withConfig(); // re-stub after reset
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));
        listener.on(new MailSendRequested(request, "retry-entry-1"));
        ArgumentCaptor<OutboxDeliveryResult> secondResult = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher).publishEvent(secondResult.capture());
        assertThat(secondResult.getValue().isAcked()).isTrue();
    }

    @Test
    void rel22_redeliveredMessageId_sendsTwice_expectedAtLeastOnce() throws Exception {
        // WO-REL-22 (section A decision, NOT a bug): the same message delivered twice
        // (RabbitMQ redelivery) sends the letter twice — mail is at-least-once by
        // contract (see MailSender javadoc), no inbox dedup by design.
        withConfig();
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));
        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        MailSendRequested redelivered = new MailSendRequested(request, "same-message-id");
        listener.on(redelivered);
        listener.on(redelivered);
        verify(javaMailSender, org.mockito.Mockito.times(2)).send(any(MimeMessage.class));
    }
}
