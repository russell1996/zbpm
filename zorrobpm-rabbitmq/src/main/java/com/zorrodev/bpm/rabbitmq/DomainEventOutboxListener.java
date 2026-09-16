package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes domain event envelopes to the zorrobpm.events topic exchange (ADR-7, WO-EVT-2, WO-EVT-7).
 * Routing key = process.{pdKey}.{type}[.{elementId}] with sanitization.
 * WO-REL-12 (R-02/R-06): sent with CorrelationData id = outbox entry id, so the broker confirm
 * is matched back to the DB row (markPublished only after ACK).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainEventOutboxListener {

    private final RabbitTemplate rabbitTemplate;

    @EventListener
    public void on(DomainEventPublished event) {
        Map<String, Object> envelope = event.getEnvelope();
        String type = (String) envelope.get("type");
        String pdKey = (String) envelope.get("processDefinitionKey");
        String elementId = (String) envelope.get("elementId");

        String routingKey = buildRoutingKey(type, pdKey, elementId);

        rabbitTemplate.convertAndSend(
            RabbitConfiguration.EVENTS_EXCHANGE,
            routingKey,
            envelope,
            // WO-REL-12 (R-06): carry the outbox id on the message properties too — the broker
            // return callback only sees the returned message (no CorrelationData object), so
            // without this it cannot match the failure back to the DB row.
            m -> {
                m.getMessageProperties().setCorrelationId(event.getOutboxId());
                return m;
            },
            new CorrelationData(event.getOutboxId()));

        log.info("Published domain event to {}: routingKey={}, eventId={}",
            RabbitConfiguration.EVENTS_EXCHANGE, routingKey, envelope.get("id"));
    }

    /**
     * Builds hierarchical routing key: process.{pdKey}.{type}[.{elementId}]
     * Sanitizes pdKey and elementId: any char outside [A-Za-z0-9_-] → _.
     * If pdKey is null, falls back to type only (backward compat).
     */
    static String buildRoutingKey(String type, String pdKey, String elementId) {
        if (pdKey == null || pdKey.isBlank()) {
            return type; // backward compat: no pdKey → type-only routing
        }
        StringBuilder sb = new StringBuilder();
        sb.append("process.").append(sanitize(pdKey)).append('.').append(type);
        if (elementId != null && !elementId.isBlank()) {
            sb.append('.').append(sanitize(elementId));
        }
        return sb.toString();
    }

    /** Replace any char outside [A-Za-z0-9_-] with _ (WO-EVT-7 sanitization). */
    static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
