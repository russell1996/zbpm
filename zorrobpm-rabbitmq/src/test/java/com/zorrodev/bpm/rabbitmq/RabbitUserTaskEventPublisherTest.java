package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.UserTaskEventMessage;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitUserTaskEventPublisherTest {

    private final RabbitOperations template = mock(RabbitOperations.class);
    private final RabbitOperations channelOperations = mock(RabbitOperations.class);
    private final RabbitUserTaskEventPublisher publisher = new RabbitUserTaskEventPublisher(template, Duration.ofSeconds(3));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void dedicatedChannel() {
        when(template.invoke(any())).thenAnswer(invocation ->
            ((RabbitOperations.OperationsCallback<Object>) invocation.getArgument(0)).doInRabbit(channelOperations));
    }

    @Test
    void sendsEveryMessageInOrderAndWaitsForTheConfirms() {
        UserTaskEventMessage created = message("CREATED", "user-task.created");
        UserTaskEventMessage completed = message("COMPLETED", "user-task.completed");

        publisher.publish(List.of(created, completed));

        InOrder order = inOrder(channelOperations);
        order.verify(channelOperations).execute(any());
        order.verify(channelOperations).send(eq(UserTaskEvents.EXCHANGE), eq("user-task.created"), any(Message.class));
        order.verify(channelOperations).send(eq(UserTaskEvents.EXCHANGE), eq("user-task.completed"), any(Message.class));
        order.verify(channelOperations).waitForConfirmsOrDie(3000L);
    }

    @Test
    void messageCarriesThePayloadAndTheEventId() {
        UserTaskEventMessage created = message("CREATED", "user-task.created");

        publisher.publish(List.of(created));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(channelOperations).send(anyString(), anyString(), captor.capture());
        Message message = captor.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(created.payload());
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(created.eventId().toString());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getType()).isEqualTo("CREATED");
        assertThat(message.getMessageProperties().getTimestamp().toInstant()).isEqualTo(created.occurredAt());
        assertThat((String) message.getMessageProperties().getHeader(RabbitUserTaskEventPublisher.USER_TASK_ID_HEADER))
            .isEqualTo(created.userTaskId().toString());
    }

    @Test
    void unconfirmedBatchFails() {
        doThrow(new AmqpException("nack")).when(channelOperations).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> publisher.publish(List.of(message("CREATED", "user-task.created"))))
            .isInstanceOf(AmqpException.class);
    }

    @Test
    void emptyBatchTouchesNothing() {
        publisher.publish(List.of());

        verify(template, never()).invoke(any());
    }

    private static UserTaskEventMessage message(String type, String routingKey) {
        UUID eventId = UUID.randomUUID();
        return new UserTaskEventMessage(eventId, type, routingKey, UUID.randomUUID(), Instant.parse("2026-10-02T10:15:30Z"),
            "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"name\":\"Согласовать\"}");
    }
}
