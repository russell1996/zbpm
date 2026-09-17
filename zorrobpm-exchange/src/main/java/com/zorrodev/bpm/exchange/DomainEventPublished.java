package com.zorrodev.bpm.exchange;

import lombok.Getter;

import java.util.Map;

/**
 * Spring event published when a domain event envelope is ready to be sent to RabbitMQ (WO-EVT-2).
 * outboxId = outbox entry id, used as the RabbitMQ CorrelationData id so the broker confirm
 * can be matched back to the DB row (WO-REL-12 R-02/R-06). Consumers may use it as stable
 * messageId for deduplication (at-least-once contract).
 *
 * <p>WO-OBS-8: {@code traceParent} is the W3C traceparent of the outbox-processing span
 * (nullable — null when the entry was enqueued outside any traced context). Explicit ctors
 * so the pre-OBS-8 2-arg call sites keep compiling untouched.
 */
@Getter
public class DomainEventPublished {
    private final Map<String, Object> envelope;
    private final String outboxId;
    private final String traceParent;

    public DomainEventPublished(Map<String, Object> envelope, String outboxId) {
        this(envelope, outboxId, null);
    }

    public DomainEventPublished(Map<String, Object> envelope, String outboxId, String traceParent) {
        this.envelope = envelope;
        this.outboxId = outboxId;
        this.traceParent = traceParent;
    }
}
