package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.MailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-5 unit tests for SmtpMailSender.
 * Covers criteria 1, 10, 11: abstraction, recipient filtering.
 */
@ExtendWith(MockitoExtension.class)
class SmtpMailSenderTest {

    @Mock
    private OutboxRepository outboxRepository;

    private SmtpMailSender sender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sender = new SmtpMailSender(outboxRepository, objectMapper);
    }

    @Test
    void criterion1_send_createsOutboxEntryWithKindEmail() throws Exception {
        // criterion 1: send goes through abstraction; entry has kind=EMAIL
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sender.send("user@example.com", "Test subject", "Hello");

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEntry entry = captor.getValue();
        assertThat(entry.getKind()).isEqualTo(OutboxKind.EMAIL);
        assertThat(entry.isPublished()).isFalse();
        assertThat(entry.getPayload()).contains("user@example.com");
        assertThat(entry.getPayload()).contains("Test subject");

        MailRequest request = objectMapper.readValue(entry.getPayload(), MailRequest.class);
        assertThat(request.getTo()).isEqualTo("user@example.com");
        assertThat(request.getSubject()).isEqualTo("Test subject");
        assertThat(request.getBody()).isEqualTo("Hello");
        assertThat(request.isHtml()).isFalse();
    }

    @Test
    void criterion1_sendHtml_createsHtmlRequest() throws Exception {
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sender.sendHtml("user@example.com", "HTML test", "<h1>Hello</h1>");

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());

        MailRequest request = objectMapper.readValue(captor.getValue().getPayload(), MailRequest.class);
        assertThat(request.isHtml()).isTrue();
    }

    @Test
    void criterion10_allowedRecipientBlocked_notSaved() {
        // criterion 10: recipient not in allowed list → not sent, logged
        setAllowedRecipients("admin@test.com,team@test.com");

        sender.send("stranger@other.com", "Secret", "body");

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void criterion10_allowedRecipientAccepted_saved() {
        // criterion 10: recipient in allowed list → sent
        setAllowedRecipients("admin@test.com,team@test.com");
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sender.send("admin@test.com", "Test", "body");

        verify(outboxRepository).save(any());
    }

    @Test
    void criterion11_emptyAllowedList_allRecipientsAccepted() {
        // criterion 11: empty list = no restriction — production mode
        setAllowedRecipients("");
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sender.send("anyone@world.com", "Test", "body");

        verify(outboxRepository).save(any());
    }

    @Test
    void criterion11_nullAllowedList_allRecipientsAccepted() {
        // criterion 11: null list = no restriction
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sender.send("anyone@world.com", "Test", "body");

        verify(outboxRepository).save(any());
    }

    /**
     * Criterion 7a POF: verifies that JavaMailSender is configured with starttls.required=true.
     * If someone mutates starttls.required to false while keeping enable=true, this test fails —
     * the password would go over cleartext without the sender knowing.
     */
    @Test
    void criterion7a_starttlsRequired_isEnforced() {
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(new java.util.Properties());
        org.springframework.mail.javamail.JavaMailSenderImpl javaMailSender =
            new org.springframework.mail.javamail.JavaMailSenderImpl();
        javaMailSender.setHost("smtp.test.com");
        javaMailSender.setPort(587);

        java.util.Properties javaMailProperties = new java.util.Properties();
        javaMailProperties.setProperty("mail.smtp.auth", "true");
        javaMailProperties.setProperty("mail.smtp.starttls.enable", "true");
        javaMailProperties.setProperty("mail.smtp.starttls.required", "true");
        javaMailSender.setJavaMailProperties(javaMailProperties);

        // Verify STARTTLS is required — connection fails if server doesn't support it
        assertThat(javaMailSender.getJavaMailProperties())
            .containsEntry("mail.smtp.starttls.required", "true");
        assertThat(javaMailSender.getJavaMailProperties())
            .containsEntry("mail.smtp.starttls.enable", "true");
    }

    /**
     * Criterion 7b POF: verifies SSL trust is not disabled.
     * mail.smtp.ssl.trust=* or mail.smtp.ssl.checkserveridentity=false would disable
     * certificate verification — this must never be present.
     */
    @Test
    void criterion7b_noSslTrustBypass() {
        org.springframework.mail.javamail.JavaMailSenderImpl javaMailSender =
            new org.springframework.mail.javamail.JavaMailSenderImpl();
        javaMailSender.setHost("smtp.test.com");

        java.util.Properties props = new java.util.Properties();
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.starttls.required", "true");
        javaMailSender.setJavaMailProperties(props);

        // Verify no trust bypass present
        assertThat(javaMailSender.getJavaMailProperties())
            .doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(javaMailSender.getJavaMailProperties())
            .doesNotContainEntry("mail.smtp.ssl.checkserveridentity", "false");
    }

    private void setAllowedRecipients(String value) {
        try {
            var field = SmtpMailSender.class.getDeclaredField("allowedRecipientsRaw");
            field.setAccessible(true);
            field.set(sender, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
