package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DomainEventOutboxListenerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private DomainEventOutboxListener listener;

    @Test
    void on_publishesToTopicExchangeWithRoutingKeyType() {
        Map<String, Object> envelope = Map.of(
            "eventId", "test-event-id",
            "type", "process-instance.completed",
            "occurredAt", "2026-07-19T10:00:00Z",
            "processInstanceId", "test-pi-id",
            "data", Map.of()
        );

        listener.on(new DomainEventPublished(envelope));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfiguration.EVENTS_EXCHANGE),
            eq("process-instance.completed"),
            captor.capture());

        assertThat(captor.getValue()).containsEntry("type", "process-instance.completed");
        assertThat(captor.getValue()).containsEntry("eventId", "test-event-id");
    }

    @Test
    void on_usesEventTypeAsRoutingKey() {
        Map<String, Object> envelope = Map.of(
            "eventId", "test-id",
            "type", "incident.raised",
            "data", Map.of()
        );

        listener.on(new DomainEventPublished(envelope));

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfiguration.EVENTS_EXCHANGE),
            eq("incident.raised"),
            eq(envelope));
    }
}
