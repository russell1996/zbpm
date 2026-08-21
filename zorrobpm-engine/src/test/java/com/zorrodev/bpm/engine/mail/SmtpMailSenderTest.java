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
     * Criterion 7a POF: reads actual CE application.properties and verifies STARTTLS is required.
     * If someone mutates starttls.required to false in the config, this test fails —
     * the password would go over cleartext without the sender knowing.
     * Mail properties live in zorrobpm-ce module (not engine), so we read from the project root.
     */
    @Test
    void criterion7a_starttlsRequired_isEnforced() throws Exception {
        java.util.Properties props = new java.util.Properties();
        // Read from zorrobpm-ce/src/main/resources/application.properties (one level up from engine)
        java.nio.file.Path ceProps = java.nio.file.Path.of(
            System.getProperty("user.dir")).resolve("../zorrobpm-ce/src/main/resources/application.properties");
        assertThat(ceProps)
            .as("CE application.properties must exist at %s", ceProps.toAbsolutePath())
            .exists();
        try (var is = java.nio.file.Files.newInputStream(ceProps)) {
            props.load(is);
        }

        assertThat(props.getProperty("spring.mail.properties.mail.smtp.starttls.enable"))
            .as("starttls.enable must be true").isEqualTo("true");
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.starttls.required"))
            .as("starttls.required must be true").isEqualTo("true");
    }

    /**
     * Criterion 7b POF: reads actual CE application.properties and verifies no SSL trust bypass.
     * mail.smtp.ssl.trust=* or mail.smtp.ssl.checkserveridentity=false would disable
     * certificate verification — this must never be present.
     */
    @Test
    void criterion7b_noSslTrustBypass() throws Exception {
        java.util.Properties props = new java.util.Properties();
        java.nio.file.Path ceProps = java.nio.file.Path.of(
            System.getProperty("user.dir")).resolve("../zorrobpm-ce/src/main/resources/application.properties");
        assertThat(ceProps).exists();
        try (var is = java.nio.file.Files.newInputStream(ceProps)) {
            props.load(is);
        }

        // No trust bypass: ssl.trust must not be set to *
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.ssl.trust"))
            .as("ssl.trust must not be set to * (disables certificate check)")
            .isNotEqualTo("*");
        // No identity check bypass
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity"))
            .as("ssl.checkserveridentity must not be false")
            .isNotEqualTo("false");
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
