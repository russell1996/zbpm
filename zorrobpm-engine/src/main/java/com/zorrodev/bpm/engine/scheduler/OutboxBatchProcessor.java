package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
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
    private final DomainEventEmitter domainEventEmitter;
    private final TracingSupport tracing;

    /**
     * WO-PERF-8: half the worst-case transaction (was 100). Drain rate at the default
     * 2s poll is still ~25 rows/s; at-least-once is unchanged — whatever does not fit
     * is picked up by the following ticks.
     */
    @Value("${zorrobpm.outbox.batch-size:50}")
    private int batchSize = 50;

    @Value("${zorrobpm.outbox.max-retries:5}")
    private int maxRetries;

    /**
     * WO-PERF-8: gauge sampling cadence in ticks. Two COUNT(*) per 2s tick is wasted
     * when the backlog barely moves between polls — sample every 30th tick (~60s at
     * the default poll interval) instead. First tick always samples (cold start must
     * not report stale zeroes). Fail-open: {@code <= 0} samples every tick (tests
     * that explicitly set 0; unconfigured instances use the initializer, 30).
     */
    @Value("${zorrobpm.outbox.metrics-sample-every:30}")
    private int metricsSampleEvery = 30;

    private final java.util.concurrent.atomic.AtomicLong tickCounter = new java.util.concurrent.atomic.AtomicLong(0);

    @Transactional
    public void processBatch() {
        var pending = outboxRepository.findPendingBatch(batchSize);
        // WO-QW-1 A-C-5b: gauge reads must not ride inside the writer transaction —
        // defer to afterCommit (same precedent as DeploymentPostCommitActions).
        // One registration per processBatch call at most: the sampling decision is
        // made NOW (tick cadence), the reads run later. Combined with the cadence
        // gate below, 30 ticks register exactly one afterCommit.
        // Without an active synchronization (plain unit tests calling this directly)
        // sample immediately — keeps those tests meaningful.
        long tick = tickCounter.incrementAndGet();
        boolean sampledTick = metricsSampleEvery <= 0 || (tick - 1) % metricsSampleEvery == 0;
        Runnable sampleGauges = () -> {
            bpmMetrics.setOutboxBacklog(outboxRepository.countPending());
            bpmMetrics.setOutboxQuarantine(outboxRepository.countQuarantined());
        };
        if (!sampledTick) {
            // skipped tick: no reads now, no deferral either.
        } else if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sampleGauges.run();
                    }
                });
        } else {
            sampleGauges.run();
        }
        for (OutboxEntry entry : pending) {
            // WO-OBS-8: continue the enqueue-time trace across the @Scheduled gap
            // (the poll thread was never on the author's stack — MDC alone cannot
            // cross it; the stored trace_parent can). One span + MDC per entry;
            // Spring-event listeners below inherit the scope on this thread.
            try (TracingSupport.TraceScope ignored = tracing.openChildSpan(
                    entry.getTraceParent(), "outbox.process",
                    processInstanceIdOf(entry),
                    TracingSupport.attrs("outbox.id", entry.getId().toString(),
                        "outbox.kind", String.valueOf(entry.getKind())))) {
                processOne(entry);
            }
        }
    }

    private void processOne(OutboxEntry entry) {
        try {
            OutboxKind kind = entry.getKind() != null ? entry.getKind() : OutboxKind.SERVICE_TASK;
            // WO-OBS-8: the CURRENT span is the per-entry child opened above (same
            // traceId as the stored parent) — forward ITS traceparent so the broker
            // hop links as grandchild, not sibling. Null when untraced/noop.
            String childTraceParent = tracing.captureTraceParent();
            switch (kind) {
                case DOMAIN_EVENT -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = objectMapper.readValue(entry.getPayload(), Map.class);
                    publisher.publishEvent(new DomainEventPublished(envelope, entry.getId().toString(), childTraceParent));
                    log.info("Published domain event outbox entry {}: type={}", entry.getId(), envelope.get("type"));
                }
                case SERVICE_TASK -> {
                    JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                    publisher.publishEvent(new ServiceTaskEnqueued(detail, entry.getId().toString(), childTraceParent));
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
                // WO-REL-22 (B3): emit only on the FIRST transition (markFailed is
                // conditional) — duplicate marks must not re-emit.
                if (outboxRepository.markFailed(entry.getId()) == 1) {
                    emitQuarantined(entry, nextAttempt, errorSummary);
                }
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

    /**
     * WO-OBS-8: processInstanceId for the per-entry scope/MDC. SERVICE_TASK entries
     * carry it in the payload; DOMAIN_EVENT envelopes carry it too; EMAIL has none
     * (null → scope without PI, still traced). Never throws — malformed payload is
     * the poison path's job, not tracing's.
     */
    private String processInstanceIdOf(OutboxEntry entry) {
        try {
            if (entry.getKind() == null || entry.getKind() == OutboxKind.SERVICE_TASK) {
                JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                return detail.getProcessInstanceId() != null ? detail.getProcessInstanceId().toString() : null;
            }
            if (entry.getKind() == OutboxKind.DOMAIN_EVENT) {
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(entry.getPayload(), Map.class);
                Object pi = envelope.get("processInstanceId");
                return pi != null ? pi.toString() : null;
            }
        } catch (Exception e) {
            log.debug("Could not extract processInstanceId for tracing from outbox entry {}", entry.getId());
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * WO-REL-22 (B3): quarantine alert as a regular domain event (same
     * {@code zorrobpm.events} exchange as all others) — no bespoke alerting infra.
     */
    private void emitQuarantined(OutboxEntry entry, int attempts, String errorSummary) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("outboxId", entry.getId().toString());
        data.put("kind", entry.getKind() != null ? entry.getKind().name() : null);
        data.put("attempts", attempts);
        data.put("lastError", errorSummary);
        domainEventEmitter.emit(
            com.zorrodev.bpm.contract.dto.event.DomainEventType.OUTBOX_QUARANTINED,
            null, null, null, data);
    }
}
