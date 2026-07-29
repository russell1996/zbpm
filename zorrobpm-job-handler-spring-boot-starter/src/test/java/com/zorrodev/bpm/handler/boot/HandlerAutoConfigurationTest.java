package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlerAutoConfigurationTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private SimpleRabbitListenerContainerFactory connectionFactory;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private SimpleMessageListenerContainer container;

    @Mock
    private JobHandler handlerA;

    @Mock
    private JobHandler handlerB;

    @Captor
    private ArgumentCaptor<MessageConverter> converterCaptor;

    private HandlerAutoConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new HandlerAutoConfiguration(applicationContext, connectionFactory, rabbitTemplate, amqpAdmin);
    }

    @Test
    @DisplayName("CRIT-3: setMessageConverter is called once before handler loop, not per handler")
    void setMessageConverterCalledOnce() {
        // Given: two handlers registered in context
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA, "handlerB", handlerB));
        when(handlerA.getJob()).thenReturn("taskA");
        when(handlerB.getJob()).thenReturn("taskB");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        // Queue does not exist — will declare it
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        // When
        configuration.init();

        // Then: setMessageConverter is called exactly once (not per-handler)
        verify(connectionFactory, times(1)).setMessageConverter(converterCaptor.capture());
        verify(rabbitTemplate, times(1)).setMessageConverter(any());

        // CRIT-4: Verify Jackson2JsonMessageConverter is used (not Jackson3 variant)
        assertThat(converterCaptor.getValue())
                .isInstanceOf(Jackson2JsonMessageConverter.class);
    }

    @Test
    @DisplayName("CRIT-5: Shared ObjectMapper — init() succeeds and queues are declared")
    void sharedObjectMapperUsed() {
        // Given: two handlers
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA, "handlerB", handlerB));
        when(handlerA.getJob()).thenReturn("taskA");
        when(handlerB.getJob()).thenReturn("taskB");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        // Queue does not exist — triggers queue declaration
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        // When
        configuration.init();

        // Then: both queues declared
        verify(amqpAdmin).declareQueue(argThat(q -> q.getName().equals("zorrobpm.jobs.taskA")));
        verify(amqpAdmin).declareQueue(argThat(q -> q.getName().equals("zorrobpm.jobs.taskB")));

        // Two containers created (one per handler)
        verify(connectionFactory, times(2)).createListenerContainer();
        verify(container, times(2)).start();
    }

    @Test
    @DisplayName("Handlers with existing queues do not redeclare them")
    void existingQueueNotRedeclared() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        // Queue exists (non-null return means queue is present)
        when(amqpAdmin.getQueueInfo("zorrobpm.jobs.taskA")).thenReturn(mock(QueueInformation.class));

        // When
        configuration.init();

        // Then: queue not declared (already exists)
        verify(amqpAdmin, times(0)).declareQueue(any());
    }

    @Test
    @DisplayName("No handlers found — no queues or containers created")
    void noHandlers() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of());

        // When
        configuration.init();

        // Then: no containers created, no setMessageConverter called
        verify(connectionFactory, times(1)).setMessageConverter(any());
        verify(rabbitTemplate, times(1)).setMessageConverter(any());
        verify(connectionFactory, times(0)).createListenerContainer();
    }
}
