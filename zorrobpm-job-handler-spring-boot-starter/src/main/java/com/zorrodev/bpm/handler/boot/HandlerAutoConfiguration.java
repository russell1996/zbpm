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

    @PostConstruct
    public void init() {
        this.objectMapper = new ObjectMapper();
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        // One-time converter config (CRIT-3: not in loop, CRIT-4: Jackson2 variant)
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        connectionFactory.setMessageConverter(converter);
        rabbitTemplate.setMessageConverter(converter);

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            if (amqpAdmin.getQueueInfo(queueName) == null) {
                Queue queue = new Queue(queueName, true);
                amqpAdmin.declareQueue(queue);
                log.info("Queue {} created", queueName);
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
