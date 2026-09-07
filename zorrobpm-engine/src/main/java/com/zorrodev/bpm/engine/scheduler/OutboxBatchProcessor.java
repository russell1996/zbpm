package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.exchange.MailSendRequested;
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
 *
 * WO-REL-12 (R-01/R-02/R-06): routing is decided by the explicit {@link OutboxKind} column,
 * NOT by substring guessing over the payload (a service-task payload that happens to contain
 * "type"/"eventId" substrings must still go to the job queue). The processor only publishes
 * the Spring event; the outbox row is marked {@code published} by
 * {@link OutboxDeliveryResultListener} after the broker ACKs the message (publisher confirms).
 * Until then the row stays pending and is re-published on the next poll — at-least-once
 * delivery: consumers must dedupe by the stable messageId (= outbox id, carried in
 * CorrelationData / message correlationId).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxBatchProcessor {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final BpmMetrics bpmMetrics;

    @Value("${zorrobpm.outbox.batch-size:100}")
    private int batchSize;

    @Value("${zorrobpm.outbox.max-retries:5}")
    private int maxRetries;

    @Transactional
    public void processBatch() {
        var pending = outboxRepository.findPendingBatch(batchSize);
        // WO-OBS-1: gauges sampled per batch (read-only, no behavior change).
        bpmMetrics.setOutboxBacklog(outboxRepository.countPending());
        bpmMetrics.setOutboxQuarantine(outboxRepository.countQuarantined());
        for (OutboxEntry entry : pending) {
            try {
                OutboxKind kind = entry.getKind() != null ? entry.getKind() : OutboxKind.SERVICE_TASK;
                switch (kind) {
                    case DOMAIN_EVENT -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> envelope = objectMapper.readValue(entry.getPayload(), Map.class);
                        publisher.publishEvent(new DomainEventPublished(envelope, entry.getId().toString()));
                        log.info("Published domain event outbox entry {}: type={}", entry.getId(), envelope.get("type"));
                    }
                    case SERVICE_TASK -> {
                        JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                        publisher.publishEvent(new ServiceTaskEnqueued(detail, entry.getId().toString()));
                        log.info("Published outbox entry {} for service task {}", entry.getId(), detail.getServiceTaskId());
                    }
                    case EMAIL -> {
                        MailRequest request = objectMapper.readValue(entry.getPayload(), MailRequest.class);
                        publisher.publishEvent(new MailSendRequested(request, entry.getId().toString()));
                        log.info("Published mail outbox entry {} to {}", entry.getId(), request.getTo());
                    }
                }
                // WO-REL-12 R-02: no markPublished here — the row is marked only after the
                // broker ACK arrives (OutboxDeliveryResultListener), so a lost message can't
                // look "delivered" in the DB.
            } catch (Exception e) {
                int nextAttempt = entry.getAttempts() + 1;
                String errorSummary = truncate(e.getMessage(), 500);
                if (nextAttempt >= maxRetries) {
                    outboxRepository.markFailed(entry.getId());
                    bpmMetrics.outboxFailed();
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

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
