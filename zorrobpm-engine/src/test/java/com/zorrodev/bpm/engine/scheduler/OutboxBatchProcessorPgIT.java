package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * WO-REL-10: PG-IT for outbox batch limit + poison quarantine.
 */
public class OutboxBatchProcessorPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outboxRepository;
    @Autowired TransactionTemplate txTemplate;

    private ApplicationEventPublisher publisher;
    private OutboxBatchProcessor processor;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
        publisher = mock(ApplicationEventPublisher.class);
        processor = new OutboxBatchProcessor(outboxRepository, publisher, new ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class), com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        ReflectionTestUtils.setField(processor, "batchSize", 3);
        ReflectionTestUtils.setField(processor, "maxRetries", 2);
    }

    /** Valid ServiceTaskEnqueued payload (serviceTaskId is UUID). */
    private String serviceTaskPayload() {
        return "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\"}";
    }

    /** Invalid payload that will fail deserialization. */
    private String poisonPayload() {
        return "{invalid json!!!";
    }

    private void insert(String payload) {
        insert(payload, "SERVICE_TASK");
    }

    private void insert(String payload, String kind) {
        jdbc.update(
            "INSERT INTO outbox (id, payload, created_at, published, attempts, status, kind) " +
            "VALUES (?, ?, ?, false, 0, 'PENDING', ?)",
            UUID.randomUUID(), payload, Timestamp.from(Instant.now()), kind);
    }

    /**
     * POF LIMIT: 5 entries, batch size=3 → only 3 events published.
     * WO-REL-12 (R-02): rows are NOT marked published by the processor — all 5 stay
     * published=false (pending) and wait for the broker ACK.
     */
    @Test
    void processBatch_respectsBatchSize() {
        for (int i = 0; i < 5; i++) insert(serviceTaskPayload());

        txTemplate.executeWithoutResult(s -> processor.processBatch());

        verify(publisher, times(3)).publishEvent(any(com.zorrodev.bpm.exchange.ServiceTaskEnqueued.class));
        long remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE published = false AND status != 'FAILED'", Long.class);
        assertThat(remaining)
            .as("WO-REL-12: processor must NOT mark published — all entries wait for broker ACK")
            .isEqualTo(5);
    }

    /**
     * POF POISON: invalid JSON → after maxRetries(2) → status=FAILED → skipped in next batch.
     */
    @Test
    void processBatch_quarantineAfterMaxRetries() {
        insert(poisonPayload());
        insert(serviceTaskPayload());

        // Tick 1: poison fails, good succeeds
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        verify(publisher, times(1)).publishEvent(any(com.zorrodev.bpm.exchange.ServiceTaskEnqueued.class));

        var poison = jdbc.queryForMap(
            "SELECT attempts, status FROM outbox WHERE payload LIKE '%invalid%'");
        assertThat(((Number) poison.get("attempts")).intValue()).isEqualTo(1);
        assertThat(poison.get("status")).isEqualTo("PENDING");

        // Tick 2: poison fails again → attempts=2 >= maxRetries(2) → FAILED
        txTemplate.executeWithoutResult(s -> processor.processBatch());

        poison = jdbc.queryForMap(
            "SELECT attempts, status FROM outbox WHERE payload LIKE '%invalid%'");
        assertThat(poison.get("status")).isEqualTo("FAILED");

        // Tick 3: poison is SKIPPED (status=FAILED); the good entry is published again —
        // WO-REL-12 R-02: it stays pending (no markPublished from the processor) until the
        // broker ACK arrives, so it is re-published on every poll (at-least-once).
        txTemplate.executeWithoutResult(s -> processor.processBatch());
        verify(publisher, times(3)).publishEvent(any(com.zorrodev.bpm.exchange.ServiceTaskEnqueued.class));
    }
}
