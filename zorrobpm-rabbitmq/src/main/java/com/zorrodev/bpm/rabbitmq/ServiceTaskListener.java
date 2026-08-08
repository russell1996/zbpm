package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void on(ServiceTaskEnqueued event) {
        JobDetailModel detail = event.getDetail();

        String queueName = "zorrobpm.jobs." + detail.getJob();
        if (amqpAdmin.getQueueInfo(queueName) == null) {
            Queue queue = new Queue(queueName, true);
            amqpAdmin.declareQueue(queue);
            log.info("Queue {} created", queueName);
        }

        // WO-REL-12 (R-02/R-06): CorrelationData id = outbox entry id → broker ACK is matched
        // back to the DB row (markPublished only after confirmation). The id is also carried
        // on the message properties so the return callback can match unroutable messages.
        rabbitTemplate.convertAndSend(
            queueName,
            detail,
            m -> {
                m.getMessageProperties().setCorrelationId(event.getOutboxId());
                return m;
            },
            new CorrelationData(event.getOutboxId()));
        log.info("Sent data for job {} to {}", detail.getJob(), queueName);
    }

    @RabbitListener(queues = com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration.COMPLETE_QUEUE)
    public void on(ServiceTaskCompleteData data) {
        log.info("Service task to complete message received - {}", data.getServiceTaskId());
        ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
        serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
        serviceTaskCompleted.setStatus(data.getStatus());
        serviceTaskCompleted.setErrorMessage(data.getErrorMessage());
        serviceTaskCompleted.setVariables(data.getVariables());
        publisher.publishEvent(serviceTaskCompleted);
    }
}
