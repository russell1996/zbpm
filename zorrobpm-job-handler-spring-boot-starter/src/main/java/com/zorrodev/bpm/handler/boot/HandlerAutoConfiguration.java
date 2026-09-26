package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    private ObjectMapper objectMapper;  // shared instance (CRIT-5)

    /** WO-QW-1 S-7: package-visible for the unit test (same mechanism as RabbitConfiguration). */
    static void setExchangeOnlyTrustedPackages(
            org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper mapper) {
        try {
            java.lang.reflect.Field f =
                org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper.class
                    .getDeclaredField("trustedPackages");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<String> trusted = (java.util.Set<String>) f.get(mapper);
            trusted.clear();
            trusted.add("com.zorrodev.bpm.exchange");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WO-QW-1 S-7: cannot narrow AMQP trusted packages", e);
        }
    }

    @PostConstruct
    public void init() {
        this.objectMapper = new ObjectMapper();
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        // One-time converter config (CRIT-3: not in loop, CRIT-4: Jackson2 variant).
        // WO-QW-1 S-7: narrow the deserialization allowlist to our own exchange
        // package (clear-then-add: the setter only adds, the constructor seeds
        // java.util/java.lang — we never pass a literal "*"). Sibling path:
        // RabbitConfiguration.messageConverter().
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        setExchangeOnlyTrustedPackages(
            (org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper)
                converter.getJavaTypeMapper());
        connectionFactory.setMessageConverter(converter);
        rabbitTemplate.setMessageConverter(converter);

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            if (amqpAdmin.getQueueInfo(queueName) == null) {
                // WO-REL-45: mirror the engine-side JobQueueDeclarer — DLX/DLQ
                // arguments identical, so whichever side declares first wins and
                // the second declare is a no-op (same args, no 406). A worker
                // that starts before any engine announcement still gets the DLQ.
                String dlqName = "zorrobpm.jobs." + handler.getJob() + ".dlq";
                amqpAdmin.declareExchange(
                    new org.springframework.amqp.core.DirectExchange("zorrobpm.jobs.dlx", true, false));
                amqpAdmin.declareQueue(
                    org.springframework.amqp.core.QueueBuilder.durable(dlqName).build());
                amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(
                    dlqName, org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                    "zorrobpm.jobs.dlx", dlqName, null));
                Queue queue = org.springframework.amqp.core.QueueBuilder.durable(queueName)
                    .deadLetterExchange("zorrobpm.jobs.dlx")
                    .deadLetterRoutingKey(dlqName)
                    .build();
                amqpAdmin.declareQueue(queue);
                log.info("Queue {} created (DLQ {})", queueName, dlqName);
            }
            container.setQueueNames(queueName);
            log.info("Subscribing to {}", queueName);
            // WO-REL-36: вся логика — в JobCompletionListener (разделение ошибок +
            // идемпотентная переотправка); здесь только wiring.
            container.setMessageListener(
                new JobCompletionListener(handler, rabbitTemplate, objectMapper, queueName));
            container.start();
        }

    }
}
