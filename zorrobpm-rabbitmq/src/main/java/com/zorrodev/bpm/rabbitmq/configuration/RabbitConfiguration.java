package com.zorrodev.bpm.rabbitmq.configuration;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                         ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        return template;
    }

}
