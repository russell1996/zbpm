package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-12 (R-02): the outbox row is marked published ONLY after a real broker ACK;
 * NACK/unroutable results leave the row pending and count one attempt via the existing
 * attempts/maxRetries mechanism (quarantine past maxRetries).
 */
@ExtendWith(MockitoExtension.class)
class OutboxDeliveryResultListenerTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private com.zorrodev.bpm.engine.event.DomainEventEmitter domainEventEmitter;

    private OutboxDeliveryResultListener listener;

    @BeforeEach
    void setUp() {
        listener = new OutboxDeliveryResultListener(outboxRepository, new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), domainEventEmitter);
        ReflectionTestUtils.setField(listener, "maxRetries", 5);
    }

    private OutboxEntry entry(int attempts) {
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setKind(OutboxKind.SERVICE_TASK);
        e.setPayload("{}");
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        e.setAttempts(attempts);
        return e;
    }

    @Test
    void ack_marksEntryPublished() {
        OutboxEntry e = entry(0);
        listener.on(new OutboxDeliveryResult(e.getId().toString(), true, null));

        verify(outboxRepository).markPublished(e.getId());
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), anyString());
        verify(outboxRepository, never()).markFailed(any());
    }

    @Test
    void nack_entryStaysPending_andCountsAttempt() {
        OutboxEntry e = entry(0);
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));

        listener.on(new OutboxDeliveryResult(e.getId().toString(), false, "unroutable: 312 No route"));

        verify(outboxRepository, never()).markPublished(e.getId());
        verify(outboxRepository).recordFailure(eq(e.getId()), eq(1), eq("unroutable: 312 No route"));
        verify(outboxRepository, never()).markFailed(any());
    }

    @Test
    void nack_afterMaxRetries_quarantinesEntry() {
        OutboxEntry e = entry(4); // next attempt 5 >= maxRetries(5) → FAILED
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));

        listener.on(new OutboxDeliveryResult(e.getId().toString(), false, "unroutable"));

        verify(outboxRepository).markFailed(e.getId());
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), anyString());
        verify(outboxRepository, never()).markPublished(any());
    }

    @Test
    void nack_unknownEntry_ignoredSafely() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.findById(id)).thenReturn(Optional.empty());

        listener.on(new OutboxDeliveryResult(id.toString(), false, "boom"));

        verify(outboxRepository, never()).markPublished(any());
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), anyString());
        verify(outboxRepository, never()).markFailed(any());
    }

    @Test
    void invalidOutboxId_ignoredSafely() {
        listener.on(new OutboxDeliveryResult("not-a-uuid", false, "boom"));

        verify(outboxRepository, never()).markPublished(any());
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), anyString());
        verify(outboxRepository, never()).markFailed(any());
    }

    @Test
    void rel22_firstQuarantine_emitsOutboxQuarantined() {
        // WO-REL-22 (B3): first transition to FAILED emits the alert event.
        OutboxEntry e = entry(4); // next attempt 5 >= maxRetries(5) → FAILED
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));
        when(outboxRepository.markFailed(e.getId())).thenReturn(1);

        listener.on(new OutboxDeliveryResult(e.getId().toString(), false, "unroutable"));

        verify(domainEventEmitter).emit(
            eq(com.zorrodev.bpm.contract.dto.event.DomainEventType.OUTBOX_QUARANTINED),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.argThat(data ->
                e.getId().toString().equals(data.get("outboxId"))));
    }

    @Test
    void rel22_duplicateResultAfterQuarantine_emitsNothing() {
        // WO-REL-22 (B3): exactly-once per row — a duplicate delivery result landing
        // after quarantine re-marks nothing and emits nothing.
        OutboxEntry e = entry(5);
        e.setStatus("FAILED");
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));

        listener.on(new OutboxDeliveryResult(e.getId().toString(), false, "unroutable"));

        verify(outboxRepository, never()).markFailed(any());
        verify(domainEventEmitter, org.mockito.Mockito.never()).emit(
            any(), any(), any(), any(), any());
    }
}
