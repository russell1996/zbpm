package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DomainEventOutboxListenerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private DomainEventOutboxListener listener;

    @Test
    void on_publishesWithHierarchicalRoutingKey() {
        Map<String, Object> envelope = Map.of(
            "eventId", "test-event-id",
            "type", "process-instance.completed",
            "processDefinitionKey", "vacation",
            "occurredAt", "2026-07-19T10:00:00Z",
            "processInstanceId", "test-pi-id",
            "data", Map.of()
        );

        listener.on(new DomainEventPublished(envelope));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfiguration.EVENTS_EXCHANGE),
            eq("process.vacation.process-instance.completed"),
            captor.capture());

        assertThat(captor.getValue()).containsEntry("type", "process-instance.completed");
        assertThat(captor.getValue()).containsEntry("processDefinitionKey", "vacation");
    }

    @Test
    void on_withElementId_appendsToRoutingKey() {
        Map<String, Object> envelope = Map.of(
            "eventId", "test-id",
            "type", "user-task.created",
            "processDefinitionKey", "vacation",
            "elementId", "Approve",
            "data", Map.of()
        );

        listener.on(new DomainEventPublished(envelope));

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitConfiguration.EVENTS_EXCHANGE),
            eq("process.vacation.user-task.created.Approve"),
            eq(envelope));
    }

    @Test
    void on_withoutPdKey_fallsBackToTypeOnly() {
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

    @Test
    void buildRoutingKey_sanitizesDotsInPdKey() {
        String key = DomainEventOutboxListener.buildRoutingKey("user-task.created", "Ap.prove", null);
        assertThat(key).isEqualTo("process.Ap_prove.user-task.created");
    }

    @Test
    void buildRoutingKey_sanitizesDotsInElementId() {
        String key = DomainEventOutboxListener.buildRoutingKey("user-task.created", "vacation", "Ap.prove");
        assertThat(key).isEqualTo("process.vacation.user-task.created.Ap_prove");
    }

    @Test
    void buildRoutingKey_sanitizesSpaces() {
        String key = DomainEventOutboxListener.buildRoutingKey("user-task.created", "my process", null);
        assertThat(key).isEqualTo("process.my_process.user-task.created");
    }

    @Test
    void buildRoutingKey_preservesAllowedChars() {
        String key = DomainEventOutboxListener.buildRoutingKey("user-task.created", "my-process_v2", "task-1");
        assertThat(key).isEqualTo("process.my-process_v2.user-task.created.task-1");
    }

    @Test
    void buildRoutingKey_noPdKey_returnsTypeOnly() {
        String key = DomainEventOutboxListener.buildRoutingKey("process-instance.completed", null, null);
        assertThat(key).isEqualTo("process-instance.completed");
    }

    @Test
    void sanitize_replacesIllegalChars() {
        assertThat(DomainEventOutboxListener.sanitize("a.b c")).isEqualTo("a_b_c");
        assertThat(DomainEventOutboxListener.sanitize("clean")).isEqualTo("clean");
        assertThat(DomainEventOutboxListener.sanitize("a/b\\c")).isEqualTo("a_b_c");
    }
}
