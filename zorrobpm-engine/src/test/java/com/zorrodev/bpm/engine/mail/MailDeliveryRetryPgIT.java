package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.scheduler.OutboxDeliveryResultListener;
import com.zorrodev.bpm.exchange.MailSendRequested;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-INT-5 criterion 5/6: PgIT proving the outbox retry mechanism works end-to-end
 * with a real PostgreSQL database.
 *
 * <p>Flow tested:
 * <ol>
 *   <li>EMAIL entry inserted into outbox</li>
 *   <li>{@link OutboxBatchProcessor#processBatch()} publishes {@link MailSendRequested}</li>
 *   <li>MailDeliveryListener (not loaded in test profile) would handle the event —
 *       we simulate its outcome by publishing {@link OutboxDeliveryResult} directly</li>
 *   <li>{@link OutboxDeliveryResultListener} records the failure ( nack ) or marks published ( ack )</li>
 *   <li>Entry stays unpublished → next batch poll re-publishes it (retry)</li>
 * </ol>
 *
 * <p>This proves that a transient SMTP failure leaves the entry pending for retry,
 * and that the retry mechanism is wired correctly through the outbox framework.
 */
@Tag("pg")
public class MailDeliveryRetryPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outboxRepository;
    @Autowired TransactionTemplate txTemplate;

    private ApplicationEventPublisher publisher;
    private OutboxBatchProcessor processor;
    private OutboxDeliveryResultListener resultListener;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
        publisher = mock(ApplicationEventPublisher.class);
        processor = new OutboxBatchProcessor(outboxRepository, publisher, new ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class), com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        ReflectionTestUtils.setField(processor, "batchSize", 10);
        ReflectionTestUtils.setField(processor, "maxRetries", 3);
        resultListener = new OutboxDeliveryResultListener(outboxRepository, new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class));
        ReflectionTestUtils.setField(resultListener, "maxRetries", 3);
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
        return jdbc.queryForObject(
            "SELECT attempts FROM outbox WHERE id = ?", Integer.class, id);
    }

    private String getStatus(UUID id) {
        return jdbc.queryForObject(
            "SELECT status FROM outbox WHERE id = ?", String.class, id);
    }

    private boolean isPublished(UUID id) {
        return jdbc.queryForObject(
            "SELECT published FROM outbox WHERE id = ?", Boolean.class, id);
    }

    /**
     * Criterion 5 POF: transient failure → nack → entry stays pending → retry.
     * Proves that a failed delivery does NOT mark the entry published,
     * so the next batch poll will re-publish it.
     */
    @Test
    void transientFailure_entryStaysPending_forRetry() {
        // Step 1: insert EMAIL entry
        insertEmailEntry("user@test.com", "Retry test");

        // Step 2: batch processor publishes the event
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        verify(publisher, times(1)).publishEvent(any(MailSendRequested.class));

        // Step 3: simulate nack (what MailDeliveryListener would publish on SMTP failure)
        UUID entryId = findPendingEmailId();
        txTemplate.executeWithoutResult(s -> resultListener.on(new OutboxDeliveryResult(entryId.toString(), false, "Connection timeout")));

        // Step 4: entry stays unpublished, attempt incremented
        assertThat(isPublished(entryId))
            .as("WO-INT-5: nack must NOT mark published — entry waits for retry")
            .isFalse();
        assertThat(getAttempts(entryId))
            .as("WO-INT-5: attempt count must be incremented")
            .isEqualTo(1);
        assertThat(getStatus(entryId))
            .as("WO-INT-5: status stays PENDING for retry")
            .isEqualTo("PENDING");

        // Step 5: next batch poll re-publishes the same entry (retry)
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        verify(publisher, times(2)).publishEvent(any(MailSendRequested.class));
    }

    /**
     * Criterion 6: exhausted retries → quarantine.
     * After maxRetries(3) nacks, entry is quarantined (status=FAILED) and skipped.
     */
    @Test
    void exhaustedRetries_entryQuarantined() {
        insertEmailEntry("user@test.com", "Quarantine test");
        UUID entryId = findPendingEmailId();

        // Simulate 3 nacks (maxRetries=3)
        for (int i = 1; i <= 3; i++) {
            final int attempt = i;
            txTemplate.executeWithoutResult(s -> resultListener.on(new OutboxDeliveryResult(entryId.toString(), false, "SMTP error " + attempt)));
        }

        assertThat(getStatus(entryId))
            .as("WO-INT-5: after maxRetries entry must be quarantined (FAILED)")
            .isEqualTo("FAILED");
        assertThat(getAttempts(entryId))
            .as("WO-INT-5: 3rd nack quarantines without incrementing (markFailed, not recordFailure)")
            .isEqualTo(2);

        // Next batch skips the quarantined entry
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        verify(publisher, never()).publishEvent(any(MailSendRequested.class));
    }

    /**
     * Criterion 5/6 combined: success after retry.
     * First attempt: nack → entry stays pending. Second attempt: ack → entry marked published.
     */
    @Test
    void successAfterRetry_entryMarkedPublished() {
        insertEmailEntry("user@test.com", "Success after retry");

        // First attempt: nack
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        UUID entryId = findPendingEmailId();
        txTemplate.executeWithoutResult(s -> resultListener.on(new OutboxDeliveryResult(entryId.toString(), false, "Temporary failure")));

        assertThat(isPublished(entryId)).isFalse();
        assertThat(getAttempts(entryId)).isEqualTo(1);

        // Second attempt: ack (delivery succeeded)
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        txTemplate.executeWithoutResult(s -> resultListener.on(new OutboxDeliveryResult(entryId.toString(), true, null)));

        assertThat(isPublished(entryId))
            .as("WO-INT-5: ack must mark published — delivery confirmed")
            .isTrue();
        assertThat(getStatus(entryId))
            .as("WO-INT-5: status stays PENDING (markPublished sets published=true)")
            .isEqualTo("PENDING");
    }
}
