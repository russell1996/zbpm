package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Spring event published when a domain event envelope is ready to be sent to RabbitMQ (WO-EVT-2).
 * outboxId = outbox entry id, used as the RabbitMQ CorrelationData id so the broker confirm
 * can be matched back to the DB row (WO-REL-12 R-02/R-06). Consumers may use it as stable
 * messageId for deduplication (at-least-once contract).
 */
@Getter
@RequiredArgsConstructor
public class DomainEventPublished {
    private final Map<String, Object> envelope;
    private final String outboxId;
}
