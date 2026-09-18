package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.engine.mail.MailRecipientPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
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
        sender = new SmtpMailSender(outboxRepository, objectMapper, new MailRecipientPolicy(""));
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
     * Criterion 7a POF: builds a REAL Spring context with the production mail auto-configuration,
     * fed with the spring.mail.* values from the actual CE application.properties, and asserts on
     * the ASSEMBLED JavaMailSender — not on the config file text. If starttls.required is mutated
     * to false in the config, the assembled sender loses the requirement and this test fails:
     * the password would go over cleartext without the sender knowing.
     */
    @Test
    void criterion7a_starttlsRequired_isEnforced() {
        javaMailSenderFromProductionConfig().run(ctx -> {
            assertThat(ctx).hasSingleBean(JavaMailSenderImpl.class);
            java.util.Properties runtime =
                ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(runtime.getProperty("mail.smtp.starttls.enable"))
                .as("assembled sender: starttls.enable must be true").isEqualTo("true");
            assertThat(runtime.getProperty("mail.smtp.starttls.required"))
                .as("assembled sender: starttls.required must be true").isEqualTo("true");
        });
    }

    /**
     * Criterion 7b POF: same assembled-sender assertion — no SSL trust bypass may reach the
     * runtime JavaMailSender. mail.smtp.ssl.trust=* or checkserveridentity=false in the config
     * would disable certificate verification; either mutation turns this test red.
     */
    @Test
    void criterion7b_noSslTrustBypass() {
        javaMailSenderFromProductionConfig().run(ctx -> {
            assertThat(ctx).hasSingleBean(JavaMailSenderImpl.class);
            java.util.Properties runtime =
                ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(runtime.getProperty("mail.smtp.ssl.trust"))
                .as("ssl.trust must not be * (disables certificate check)")
                .isNotEqualTo("*");
            assertThat(runtime.getProperty("mail.smtp.ssl.checkserveridentity"))
                .as("ssl.checkserveridentity must not be false")
                .isNotEqualTo("false");
        });
    }

    /**
     * Builds an ApplicationContextRunner containing the real {@link MailSenderAutoConfiguration}
     * and every non-blank spring.mail.* property from the zorrobpm-app application.properties (the
     * file the production app actually starts with). Dummy host/credentials make the auto-configuration
     * create its bean (it is @ConditionalOnProperty(spring.mail.host)); no connection is attempted.
     */
    private ApplicationContextRunner javaMailSenderFromProductionConfig() {
        java.util.Properties props = new java.util.Properties();
        // zorrobpm-app application.properties lives one level up from the engine module
        java.nio.file.Path appProps = java.nio.file.Path.of(
            System.getProperty("user.dir")).resolve("../zorrobpm-app/src/main/resources/application.properties");
        assertThat(appProps)
            .as("zorrobpm-app application.properties must exist at %s", appProps.toAbsolutePath())
            .exists();
        try (var is = java.nio.file.Files.newInputStream(appProps)) {
            props.load(is);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read " + appProps, e);
        }
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withPropertyValues(
                "spring.mail.host=smtp-under-test.invalid",
                "spring.mail.port=2525",
                "spring.mail.username=under-test",
                "spring.mail.password=under-test");
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("spring.mail.properties.")) {
                String value = props.getProperty(name);
                if (value != null && !value.isBlank()) {
                    runner = runner.withPropertyValues(name + "=" + value);
                }
            }
        }
        return runner;
    }

    private void setAllowedRecipients(String value) {
        sender = new SmtpMailSender(outboxRepository, objectMapper, new MailRecipientPolicy(value));
    }
}
