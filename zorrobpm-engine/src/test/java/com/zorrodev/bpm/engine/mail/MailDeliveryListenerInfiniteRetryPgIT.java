package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.scheduler.OutboxDeliveryResultListener;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-REL-19: reproduces the production incident where EMAIL outbox entries were retried forever
 * with attempts stuck at 0. Uses the REAL MailDeliveryListener + OutboxDeliveryResultListener chain
 * (no mocked logic) with a guaranteed-failing SMTP transport, and proves that after
 * zorrobpm.outbox.max-retries (default 5) failed deliveries the entry is quarantined (status=FAILED)
 * and stops being retried.
 */
@Tag("pg")
@Import(MailDeliveryListenerInfiniteRetryPgIT.TestConfig.class)
public class MailDeliveryListenerInfiniteRetryPgIT extends PostgresIT {

    @TestConfiguration
    @Profile("pgtest")
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        MailDeliveryListener mailDeliveryListener(ApplicationEventPublisher publisher) {
            MailConfigResolver configResolver = mock(MailConfigResolver.class);
            when(configResolver.getEffectiveConfig())
                .thenReturn(new ResolvedMailConfig("smtp.example.com", 587, "user", "pass", "from@x", null));

            JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
            Session session = Session.getDefaultInstance(new Properties());
            when(sender.createMimeMessage()).thenReturn(new MimeMessage(session));
            doThrow(new MailAuthenticationException("Authentication failed"))
                .when(sender).send(any(MimeMessage.class));

            MailTransportFactory transportFactory = mock(MailTransportFactory.class);
            when(transportFactory.build(any(), any(), any(), any())).thenReturn(sender);

            MailStatus mailStatus = mock(MailStatus.class);
            return new MailDeliveryListener(configResolver, transportFactory, publisher, mailStatus);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxBatchProcessor processor;
    @Autowired OutboxDeliveryResultListener resultListener;
    @Autowired TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
        ReflectionTestUtils.setField(resultListener, "maxRetries", 5);
    }

    private void insertEmailEntry(String to, String subject) {
        String payload = "{\"to\":\"" + to + "\",\"subject\":\"" + subject + "\",\"body\":\"test\",\"html\":false}";
        jdbc.update(
            "INSERT INTO outbox (id, payload, created_at, published, attempts, status, kind) " +
            "VALUES (?, ?, ?, false, 0, 'PENDING', 'EMAIL')",
            UUID.randomUUID(), payload, Timestamp.from(Instant.now()));
    }

    private UUID findPendingEmailId() {
        return jdbc.queryForObject(
            "SELECT id FROM outbox WHERE kind = 'EMAIL' AND published = false AND status = 'PENDING'",
            UUID.class);
    }

    private int getAttempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox WHERE id = ?", Integer.class, id);
    }

    private String getStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM outbox WHERE id = ?", String.class, id);
    }

    private boolean isPublished(UUID id) {
        return jdbc.queryForObject("SELECT published FROM outbox WHERE id = ?", Boolean.class, id);
    }

    /**
     * RED on buggy code: 5 failed deliveries leave attempts=0 / status=PENDING (infinite retry).
     * GREEN after fix: 5th failure quarantines the entry (status=FAILED), attempts=4.
     */
    @Test
    void emailRetry_exhaustsAttempts_andQuarantines() {
        insertEmailEntry("user@test.com", "Quarantine test");
        UUID entryId = findPendingEmailId();

        for (int i = 1; i <= 5; i++) {
            txTemplate.executeWithoutResult(s -> processor.processBatch());
        }

        assertThat(getStatus(entryId))
            .as("WO-REL-19: after max-retries the EMAIL entry must be quarantined (FAILED)")
            .isEqualTo("FAILED");
        assertThat(getAttempts(entryId))
            .as("WO-REL-19: 5th nack quarantines without incrementing (markFailed, not recordFailure)")
            .isEqualTo(4);
        assertThat(isPublished(entryId))
            .as("WO-REL-19: failed deliveries never mark the row published")
            .isFalse();
    }

    /**
     * Criterion 2: a successful delivery (simulated ack) after a previous failure marks the row published.
     */
    @Test
    void successAfterRetry_marksPublished() {
        insertEmailEntry("user@test.com", "Success after retry");
        UUID entryId = findPendingEmailId();

        // First poll: nack (real chain via failing transport)
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        assertThat(getAttempts(entryId)).isEqualTo(1);
        assertThat(isPublished(entryId)).isFalse();

        // Simulate the successful delivery result (same OutboxDeliveryResultListener the chain uses)
        txTemplate.executeWithoutResult(s ->
            resultListener.on(new OutboxDeliveryResult(entryId.toString(), true, null)));

        assertThat(isPublished(entryId))
            .as("WO-REL-19: a successful delivery marks the entry published")
            .isTrue();
    }
}
