package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.UserTaskEventMessage;
import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Publishes user task events to the {@code zorrobpm.user-task-events} topic exchange on a channel of
 * its own with publisher confirms: a batch counts as published only when the broker has confirmed
 * every message of it. The exchange is declared before each batch, so it exists after a broker reset.
 */
@Slf4j
public class RabbitUserTaskEventPublisher implements UserTaskEventPublisher {

    public static final String USER_TASK_ID_HEADER = "zorrobpm-user-task-id";

    private final RabbitOperations rabbitOperations;
    private final Duration confirmTimeout;

    public RabbitUserTaskEventPublisher(RabbitOperations rabbitOperations, Duration confirmTimeout) {
        this.rabbitOperations = rabbitOperations;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(List<UserTaskEventMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        rabbitOperations.invoke(operations -> {
            declareExchange(operations);
            for (UserTaskEventMessage message : messages) {
                operations.send(UserTaskEvents.EXCHANGE, message.routingKey(), toAmqp(message));
            }
            operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
            return null;
        });
    }

    /** Declares the exchange at once, when the broker is there; a missing broker is reported, not thrown. */
    public void declareExchange() {
        try {
            declareExchange(rabbitOperations);
        } catch (RuntimeException e) {
            log.warn("Exchange {} not declared now, it will be on the first publication: {}", UserTaskEvents.EXCHANGE, e.toString());
        }
    }

    private static void declareExchange(RabbitOperations operations) {
        operations.execute(channel -> channel.exchangeDeclare(UserTaskEvents.EXCHANGE, "topic", true));
    }

    static Message toAmqp(UserTaskEventMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(message.eventId().toString());
        properties.setType(message.type());
        if (message.occurredAt() != null) {
            properties.setTimestamp(Date.from(message.occurredAt()));
        }
        properties.setHeader(USER_TASK_ID_HEADER, message.userTaskId().toString());
        return new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
