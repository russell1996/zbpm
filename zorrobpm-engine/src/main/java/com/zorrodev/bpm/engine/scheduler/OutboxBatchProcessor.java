package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * WO-REL-10: Transactional batch processor for outbox entries.
 * - LIMIT :batchSize on fetch to avoid unbounded locking.
 * - After maxRetries failed attempts, entry is quarantined (status=FAILED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxBatchProcessor {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Value("${zorrobpm.outbox.batch-size:100}")
    private int batchSize;

    @Value("${zorrobpm.outbox.max-retries:5}")
    private int maxRetries;

    @Transactional
    public void processBatch() {
        var pending = outboxRepository.findPendingBatch(batchSize);
        for (OutboxEntry entry : pending) {
            try {
                if (isDomainEvent(entry.getPayload())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = objectMapper.readValue(entry.getPayload(), Map.class);
                    publisher.publishEvent(new DomainEventPublished(envelope));
                    outboxRepository.markPublished(entry.getId());
                    log.info("Published domain event outbox entry {}: type={}", entry.getId(), envelope.get("type"));
                } else {
                    JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                    publisher.publishEvent(new ServiceTaskEnqueued(detail));
                    outboxRepository.markPublished(entry.getId());
                    log.info("Published outbox entry {} for service task {}", entry.getId(), detail.getServiceTaskId());
                }
            } catch (Exception e) {
                int nextAttempt = entry.getAttempts() + 1;
                String errorSummary = truncate(e.getMessage(), 500);
                if (nextAttempt >= maxRetries) {
                    outboxRepository.markFailed(entry.getId());
                    log.error("Outbox entry {} quarantined after {} attempts (max={}): {}",
                        entry.getId(), nextAttempt, maxRetries, errorSummary);
                } else {
                    outboxRepository.recordFailure(entry.getId(), nextAttempt, errorSummary);
                    log.warn("Outbox entry {} failed (attempt {}/{}): {}",
                        entry.getId(), nextAttempt, maxRetries, errorSummary);
                }
            }
        }
    }

    private boolean isDomainEvent(String payload) {
        return payload != null && payload.contains("\"type\"") && payload.contains("\"eventId\"");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
