package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.rabbitmq.JobQueueDeclarer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-66 (A): таблица области действия returns-hook —
 * переобъявляем ТОЛЬКО default-exchange + {@code zorrobpm.jobs.*} + не DLQ.
 */
class RedeclareOnReturnTest {

    private static ReturnedMessage returned(String exchange, String routingKey) {
        return new ReturnedMessage(
            new Message("body".getBytes(), new MessageProperties()),
            312, "NO_ROUTE", exchange, routingKey);
    }

    @Test
    void jobQueueOnDefaultExchange_redeclares() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.redeclareForSend("billing")).thenReturn(true);

        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("", "zorrobpm.jobs.billing"), declarer);

        verify(declarer).redeclareForSend("billing");
    }

    @Test
    void namedExchange_neverRedeclares() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);

        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("zorrobpm.events", "zorrobpm.jobs.billing"), declarer);

        verify(declarer, never()).redeclareForSend(anyString());
    }

    @Test
    void foreignRoutingKey_neverRedeclares() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);

        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("", "someone.elses.queue"), declarer);

        verify(declarer, never()).redeclareForSend(anyString());
    }

    @Test
    void dlqRoutingKey_neverRedeclares() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);

        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("", "zorrobpm.jobs.billing.dlq"), declarer);

        verify(declarer, never()).redeclareForSend(anyString());
    }

    @Test
    void blankJobType_neverRedeclares() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);

        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("", "zorrobpm.jobs."), declarer);
        RabbitConfiguration.maybeRedeclareJobQueue(
            returned("", null), declarer);

        verify(declarer, never()).redeclareForSend(anyString());
    }
}
