package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-REL-2 + WO-AUD-1 + WO-REL-12 tests for OutboxBatchProcessor and OutboxPollerService.
 *
 * WO-REL-12 (R-02) semantics: the processor publishes the Spring event but NEVER calls
 * markPublished — the row is marked published only after the broker ACK arrives
 * (OutboxDeliveryResultListener). Until then the entry stays pending and is re-published
 * on the next poll (at-least-once; consumers dedupe by messageId = outbox id).
 */
@ExtendWith(MockitoExtension.class)
class OutboxPollerServiceTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private ObjectMapper objectMapper;

    private OutboxBatchProcessor batchProcessor;
    private OutboxPollerService poller;

    @BeforeEach
    void setUp() {
        batchProcessor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper, new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), org.mockito.Mockito.mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class), com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        poller = new OutboxPollerService(batchProcessor);
        org.springframework.test.util.ReflectionTestUtils.setField(batchProcessor, "batchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(batchProcessor, "maxRetries", 5);
    }

    private OutboxEntry entry(String jobId) throws Exception {
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setPayload("{\"serviceTaskId\":\"" + jobId + "\"}");
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        when(objectMapper.readValue(e.getPayload(), JobDetailModel.class)).thenReturn(new JobDetailModel());
        return e;
    }

    // --- Criterion #2 (WO-REL-2): publish fails then recovers → entry stays pending, no mark ---

    @Test
    void criterion2_publishFailsThenRecovers_delivered() throws Exception {
        OutboxEntry entry = entry("job1");

        // First poll: publish throws → entry stays pending, failure recorded, NOT marked published
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));
        when(objectMapper.readValue(entry.getPayload(), JobDetailModel.class)).thenReturn(new JobDetailModel());
        doThrow(new RuntimeException("MQ down")).when(publisher).publishEvent(any(ServiceTaskEnqueued.class));

        poller.pollOnce();
        verify(outboxRepository, never()).markPublished(entry.getId());
        verify(outboxRepository).recordFailure(eq(entry.getId()), eq(1), anyString());

        // Second poll: publish succeeds → event published; still NO markPublished from the
        // processor (marking happens only on broker ACK — WO-REL-12 R-02)
        reset(outboxRepository, publisher, objectMapper);
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));
        when(objectMapper.readValue(entry.getPayload(), JobDetailModel.class)).thenReturn(new JobDetailModel());

        poller.pollOnce();
        verify(publisher, times(1)).publishEvent(any(ServiceTaskEnqueued.class));
        verify(outboxRepository, never()).markPublished(entry.getId());
    }

    // --- Criterion #4 (WO-REL-2): no double publish once confirmed ---

    @Test
    void criterion4_processorNeverMarksPublished_waitsForBrokerAck() throws Exception {
        OutboxEntry entry = entry("job1");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        poller.pollOnce();

        verify(publisher, times(1)).publishEvent(any(ServiceTaskEnqueued.class));
        // WO-REL-12 R-02: markPublished is NOT the processor's job anymore — only the
        // OutboxDeliveryResultListener marks after the broker ACK.
        verify(outboxRepository, never()).markPublished(entry.getId());

        // Until an ACK arrives the entry stays pending → next poll publishes again (at-least-once)
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));
        poller.pollOnce();
        verify(publisher, times(2)).publishEvent(any(ServiceTaskEnqueued.class));
    }

    // --- Criterion #5: proof-of-failure ---
    // OLD ORDER (mark→publish): publish fails but entry already marked → LOST
    // NEW ORDER (publish → ACK → mark): publish fails → entry stays pending → RETRY

    @Test
    void criterion5_proofOfFailure_publishFails_entryStaysPending() throws Exception {
        OutboxEntry entry = entry("job5");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        // Simulate MQ failure on first publish
        doThrow(new RuntimeException("MQ down")).when(publisher).publishEvent(any(ServiceTaskEnqueued.class));

        poller.pollOnce();

        // Entry stays pending (not marked published) → next poll will retry
        verify(outboxRepository, never()).markPublished(entry.getId());
        verify(outboxRepository).recordFailure(eq(entry.getId()), eq(1), anyString());
    }

    // --- AUD-1: pollOnce delegates to batchProcessor ---

    @Test
    void aud1_pollOnce_delegatesToBatchProcessor() throws Exception {
        OutboxEntry entry = entry("job-delegate");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        poller.pollOnce();

        verify(outboxRepository).findPendingBatch(100);
        verify(publisher).publishEvent(any(ServiceTaskEnqueued.class));
        verify(outboxRepository, never()).markPublished(entry.getId());
    }
}
