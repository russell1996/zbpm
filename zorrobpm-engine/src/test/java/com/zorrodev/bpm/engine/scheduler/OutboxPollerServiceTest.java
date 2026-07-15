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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-REL-2 + WO-AUD-1 tests for OutboxBatchProcessor and OutboxPollerService:
 *  #2: publish fails then recovers → delivered
 *  #4: successful publish+mark → no double publish
 *  #5: proof-of-failure — old order (claim→publish) loses messages
 *  AUD-1: pollOnce delegates to batchProcessor.processBatch()
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
        batchProcessor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper);
        poller = new OutboxPollerService(batchProcessor);
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

    // --- Criterion #2: publish fails then recovers → delivered ---

    @Test
    void criterion2_publishFailsThenRecovers_delivered() throws Exception {
        OutboxEntry entry = entry("job1");

        // First poll: publish throws → entry stays pending
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry));
        when(objectMapper.readValue(entry.getPayload(), JobDetailModel.class)).thenReturn(new JobDetailModel());
        doThrow(new RuntimeException("MQ down")).when(publisher).publishEvent(any(ServiceTaskEnqueued.class));

        poller.pollOnce();
        verify(outboxRepository, never()).markPublished(entry.getId());

        // Second poll: publish succeeds → entry marked
        reset(outboxRepository, publisher, objectMapper);
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry));
        when(objectMapper.readValue(entry.getPayload(), JobDetailModel.class)).thenReturn(new JobDetailModel());

        poller.pollOnce();
        verify(publisher, times(1)).publishEvent(any(ServiceTaskEnqueued.class));
        verify(outboxRepository).markPublished(entry.getId());
    }

    // --- Criterion #4: no double publish ---

    @Test
    void criterion4_successfulPublishThenMark_noDoublePublish() throws Exception {
        OutboxEntry entry = entry("job1");
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry));

        poller.pollOnce();

        verify(publisher, times(1)).publishEvent(any(ServiceTaskEnqueued.class));
        verify(outboxRepository).markPublished(entry.getId());

        // Second poll: entry is published → not in pending list
        reset(outboxRepository, publisher, objectMapper);
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of());

        poller.pollOnce();

        verify(publisher, never()).publishEvent(any(ServiceTaskEnqueued.class));
    }

    // --- Criterion #5: proof-of-failure ---
    // OLD ORDER (claim→publish): publish fails but entry already marked → LOST
    // NEW ORDER (publish→mark): publish fails → entry stays pending → RETRY

    @Test
    void criterion5_proofOfFailure_publishFails_entryStaysPending() throws Exception {
        OutboxEntry entry = entry("job5");
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry));

        // Simulate MQ failure on first publish
        doThrow(new RuntimeException("MQ down")).when(publisher).publishEvent(any(ServiceTaskEnqueued.class));

        poller.pollOnce();

        // With NEW order (publish→mark): markPublished NOT called because publish failed
        verify(outboxRepository, never()).markPublished(entry.getId());
        // Entry stays pending → next poll will retry
    }

    // --- AUD-1: pollOnce delegates to batchProcessor ---

    @Test
    void aud1_pollOnce_delegatesToBatchProcessor() throws Exception {
        OutboxEntry entry = entry("job-delegate");
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry));

        poller.pollOnce();

        verify(outboxRepository).findByPublishedFalseOrderByCreatedAtAsc();
        verify(publisher).publishEvent(any(ServiceTaskEnqueued.class));
        verify(outboxRepository).markPublished(entry.getId());
    }
}
