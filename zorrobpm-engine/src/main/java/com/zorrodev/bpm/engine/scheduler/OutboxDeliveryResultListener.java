package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * WO-REL-12 (R-02): reacts to broker delivery results for outbox entries.
 *
 * <p>The batch processor publishes the message event but never marks the row {@code published}.
 * Only here, after a real broker ACK, is the row marked published. NACK/unroutable results
 * leave the row pending and count one attempt via the existing attempts/maxRetries mechanism;
 * past maxRetries the entry is quarantined (status=FAILED) and skipped by the poller.
 *
 * <p>At-least-once: a crash between broker ACK and this transaction's commit re-publishes the
 * entry on the next poll. Consumers must dedupe by the stable messageId (outbox id, carried in
 * CorrelationData / message correlationId).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDeliveryResultListener {

    private final OutboxRepository outboxRepository;
    private final BpmMetrics bpmMetrics;
    private final DomainEventEmitter domainEventEmitter;

    @Value("${zorrobpm.outbox.max-retries:5}")
    private int maxRetries;

    @EventListener
    @Transactional
    public void on(OutboxDeliveryResult result) {
        UUID outboxId;
        try {
            outboxId = UUID.fromString(result.getOutboxId());
        } catch (IllegalArgumentException e) {
            log.warn("Outbox delivery result with invalid outbox id: {}", result.getOutboxId());
            return;
        }

        if (result.isAcked()) {
            outboxRepository.markPublished(outboxId);
            // WO-OBS-1: true delivery (broker ACK), not enqueue — the honest published signal.
            bpmMetrics.outboxPublished();
            log.info("Outbox entry {} confirmed by broker (ACK)", outboxId);
            return;
        }

        OutboxEntry entry = outboxRepository.findById(outboxId).orElse(null);
        if (entry == null) {
            log.warn("Outbox delivery result for unknown entry {}: {}", outboxId, result.getCause());
            return;
        }
        int nextAttempt = entry.getAttempts() + 1;
        String errorSummary = truncate(result.getCause(), 500);
        if (nextAttempt >= maxRetries) {
            // WO-REL-22 (B3): emit only on the FIRST transition — a duplicate delivery
            // result landing after quarantine re-marks nothing and emits nothing.
            // Skip already-quarantined rows outright (no pointless update).
            if (!"FAILED".equals(entry.getStatus())
                && outboxRepository.markFailed(outboxId) == 1) {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("outboxId", outboxId.toString());
                data.put("kind", entry.getKind() != null ? entry.getKind().name() : null);
                data.put("attempts", nextAttempt);
                data.put("lastError", errorSummary);
                domainEventEmitter.emit(
                    com.zorrodev.bpm.contract.dto.event.DomainEventType.OUTBOX_QUARANTINED,
                    null, null, null, data);
            }
            bpmMetrics.outboxFailed();
            log.error("Outbox entry {} quarantined after {} failed deliveries (max={}): {}",
                outboxId, nextAttempt, maxRetries, errorSummary);
        } else {
            outboxRepository.recordFailure(outboxId, nextAttempt, errorSummary);
            // WO-OBS-1: broker-level failure (NACK) — distinct from permanent quarantine above.
            bpmMetrics.rabbitPublishFailed();
            log.warn("Outbox entry {} delivery failed (attempt {}/{}): {}",
                outboxId, nextAttempt, maxRetries, errorSummary);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
