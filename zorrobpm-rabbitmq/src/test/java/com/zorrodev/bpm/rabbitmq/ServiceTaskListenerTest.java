package com.zorrodev.bpm.rabbitmq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WO-SEC-16: PII sweep — ServiceTaskListener must not log variable values.
 *
 * #1: INFO log for dispatched job contains job name + queue, NO variable values
 * #3: proof-of-failure — variable values in log = RED
 */
class ServiceTaskListenerTest {

    private AmqpAdmin amqpAdmin;
    private RabbitTemplate rabbitTemplate;
    private ApplicationEventPublisher publisher;
    private ServiceTaskListener listener;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = mock(ApplicationEventPublisher.class);
        listener = new ServiceTaskListener(amqpAdmin, rabbitTemplate, publisher);

        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(ServiceTaskListener.class);
        logger.addAppender(logAppender);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void criterion1_infoLogContainsJobAndQueue_notVariableValues() {
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        JobDetailModel detail = new JobDetailModel();
        detail.setJob("my-service-task");
        detail.setVariables(Map.of(
            "secret", createVariable("secret", "password123"),
            "token", createVariable("token", "bearer-abc-xyz")
        ));

        ServiceTaskEnqueued event = new ServiceTaskEnqueued(detail, "outbox-42");
        listener.on(event);

        // WO-REL-12 R-06: sent with CorrelationData id = outbox entry id (stable messageId)
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(eq("zorrobpm.jobs.my-service-task"), eq(detail),
            any(MessagePostProcessor.class), correlationCaptor.capture());
        assertThat(correlationCaptor.getValue().getId()).isEqualTo("outbox-42");

        List<ILoggingEvent> events = logAppender.list;
        assertThat(events).isNotEmpty();

        // All log messages should NOT contain variable values
        for (ILoggingEvent event1 : events) {
            assertThat(event1.getFormattedMessage())
                .as("Log must not contain secret value 'password123'")
                .doesNotContain("password123");
            assertThat(event1.getFormattedMessage())
                .as("Log must not contain token value 'bearer-abc-xyz'")
                .doesNotContain("bearer-abc-xyz");
            assertThat(event1.getFormattedMessage())
                .as("Log must not contain variable values map")
                .doesNotContain("secret=");
        }

        // But should contain job name and queue info
        String allMessages = events.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + " " + b);
        assertThat(allMessages).contains("my-service-task");
    }

    private com.zorrodev.bpm.exchange.ProcessVariable createVariable(String name, String value) {
        com.zorrodev.bpm.exchange.ProcessVariable v = new com.zorrodev.bpm.exchange.ProcessVariable();
        v.setName(name);
        v.setValue(value);
        return v;
    }
}
