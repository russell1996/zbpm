package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
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

import java.util.List;
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
            container.setMessageListener(message -> {
                try {
                    JobDetailModel model = objectMapper.readValue(message.getBody(), JobDetailModel.class);

                    ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
                    completeData.setServiceTaskId(model.getServiceTaskId());
                    try {
                        List<ProcessVariable> result = handler.handleJob(model).stream().map(x -> {
                            ProcessVariable v = new ProcessVariable();
                            v.setName(x.getName());
                            v.setValue(x.getValue());
                            v.setType(x.getType().toString());
                            return v;
                        }).toList();
                        completeData.setStatus("SUCCESS");
                        completeData.setVariables(result);
                    } catch (Exception e) {
                        // the worker's logic failed: report the error back so the engine applies retries and,
                        // once they are exhausted, raises an incident carrying this message
                        completeData.setStatus("FAILED");
                        completeData.setErrorMessage(e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                        log.warn("Job '{}' handler failed: {}", handler.getJob(), completeData.getErrorMessage());
                    }
                    rabbitTemplate.convertAndSend("zorrobpm.complete-service-task", completeData);
                } catch (Exception e) {
                    log.error("Failed to deserialize message for queue {}: {}", queueName, e.getMessage());
                }
            });
            container.start();
        }

    }
}
