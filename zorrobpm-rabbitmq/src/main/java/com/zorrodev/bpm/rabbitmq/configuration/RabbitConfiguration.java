package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Configuration
public class RabbitConfiguration {

    /** Queue the engine consumes service-task completions from. */
    public static final String COMPLETE_QUEUE = "zorrobpm.complete-service-task";
    /** Dead-letter exchange/queue: a completion that keeps failing is parked here instead of being
     *  redelivered forever (a poison message would otherwise block the queue). */
    public static final String COMPLETE_DLX = "zorrobpm.complete-service-task.dlx";
    public static final String COMPLETE_DLQ = "zorrobpm.complete-service-task.dlq";

    /** Topic exchange for domain events (ADR-7, WO-EVT-2). Routing key = event type. */
    public static final String EVENTS_EXCHANGE = "zorrobpm.events";

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    Queue completeServiceTaskQueue() {
        return QueueBuilder.durable(COMPLETE_QUEUE)
            .deadLetterExchange(COMPLETE_DLX)
            .deadLetterRoutingKey(COMPLETE_DLQ)
            .build();
    }

    @Bean
    DirectExchange completeServiceTaskDlx() {
        return new DirectExchange(COMPLETE_DLX);
    }

    @Bean
    Queue completeServiceTaskDlq() {
        return QueueBuilder.durable(COMPLETE_DLQ).build();
    }

    @Bean
    Binding completeServiceTaskDlqBinding() {
        return BindingBuilder.bind(completeServiceTaskDlq()).to(completeServiceTaskDlx()).with(COMPLETE_DLQ);
    }

    @Bean
    TopicExchange domainEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    /**
     * WO-REL-12 (R-02): publisher confirms + returns. Requires
     * {@code spring.rabbitmq.publisher-confirm-type=correlated} and
     * {@code spring.rabbitmq.publisher-returns=true} (applied to this template by the
     * {@link RabbitTemplateConfigurer}). Every outbox message is sent with a
     * {@code CorrelationData} whose id = outbox entry id; the confirm/return callbacks publish
     * an {@link OutboxDeliveryResult} back to the engine, which marks the row published only
     * after a real ACK (never right after convertAndSend).
     *
     * <p>Unroutable messages (mandatory mode) produce BOTH a return and a confirm with
     * {@code ack=true}. The return arrives first; ids seen there are recorded so the misleading
     * {@code ack=true} confirm is suppressed — otherwise a lost message would be marked
     * {@code published} in the DB.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                         ConnectionFactory connectionFactory,
                                         ApplicationEventPublisher publisher) {
        Set<String> returnedIds = ConcurrentHashMap.newKeySet();
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null || correlationData.getId() == null) {
                log.warn("Broker confirm without CorrelationData id (ack={}, cause={})", ack, cause);
                return;
            }
            String id = correlationData.getId();
            if (ack && returnedIds.remove(id)) {
                // basic.return arrived first for this id: message was unroutable, the failure
                // was already reported — this ack=true only means the broker accepted it on
                // the exchange, NOT that it was delivered.
                log.info("Broker confirm for outbox entry {} suppressed: message returned as unroutable", id);
                return;
            }
            if (!ack) {
                returnedIds.remove(id);
            }
            log.info("Broker confirm for outbox entry {}: ack={}, cause={}", id, ack, cause);
            publisher.publishEvent(new OutboxDeliveryResult(id, ack, cause));
        });
        template.setReturnsCallback(returned -> {
            String correlationId = returned.getMessage() != null
                ? returned.getMessage().getMessageProperties().getCorrelationId()
                : null;
            log.warn("Broker returned unroutable message: replyCode={}, replyText={}, correlationId={}",
                returned.getReplyCode(), returned.getReplyText(), correlationId);
            if (correlationId != null) {
                returnedIds.add(correlationId);
                publisher.publishEvent(new OutboxDeliveryResult(
                    correlationId, false,
                    "unroutable: " + returned.getReplyCode() + " " + returned.getReplyText()));
            }
        });
        return template;
    }

}
