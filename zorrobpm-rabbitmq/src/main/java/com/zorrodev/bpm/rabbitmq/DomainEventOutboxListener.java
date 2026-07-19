package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes domain event envelopes to the zorrobpm.events topic exchange (ADR-7, WO-EVT-2).
 * Routing key = event type (e.g. "process-instance.completed").
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

        rabbitTemplate.convertAndSend(
            RabbitConfiguration.EVENTS_EXCHANGE,
            type,
            envelope);

        log.info("Published domain event to {}: type={}, eventId={}",
            RabbitConfiguration.EVENTS_EXCHANGE, type, envelope.get("eventId"));
    }
}
