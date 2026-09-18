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

    @Test
    @DisplayName("WO-QW-1 S-7: converter allowlist is exactly com.zorrodev.bpm.exchange")
    void converterTrustsOnlyExchangePackage() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        configuration.init();

        verify(connectionFactory, times(1)).setMessageConverter(converterCaptor.capture());
        assertThat(converterCaptor.getValue()).isInstanceOf(Jackson2JsonMessageConverter.class);
        Object mapper = ((Jackson2JsonMessageConverter) converterCaptor.getValue()).getJavaTypeMapper();
        assertThat(mapper.getClass().getSimpleName()).isEqualTo("DefaultJackson2JavaTypeMapper");
        @SuppressWarnings("unchecked")
        java.util.Set<String> trusted = readTrustedPackages(mapper);
        assertThat(trusted).containsExactly("com.zorrodev.bpm.exchange");
    }

    private static java.util.Set<String> readTrustedPackages(Object mapper) {
        Class<?> c = mapper.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("trustedPackages");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Set<String> trusted = (java.util.Set<String>) f.get(mapper);
                return trusted;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read trustedPackages", e);
            }
        }
        throw new AssertionError("trustedPackages field not found on " + mapper.getClass());
    }

    @Test
    @DisplayName("WO-REL-36: listener is a JobCompletionListener (split error handling + idempotent resend)")
    void listenerIsJobCompletionListener() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        configuration.init();

        ArgumentCaptor<org.springframework.amqp.core.MessageListener> listenerCaptor =
                ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container).setMessageListener(listenerCaptor.capture());
        assertThat(listenerCaptor.getValue()).isInstanceOf(JobCompletionListener.class);
    }

    @Test
    @DisplayName("WO-REL-36: transport failure on completion send propagates (no silent success)")
    void listenerTransportFailurePropagates() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        configuration.init();

        ArgumentCaptor<org.springframework.amqp.core.MessageListener> listenerCaptor =
                ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container).setMessageListener(listenerCaptor.capture());
        org.springframework.amqp.core.MessageListener listener = listenerCaptor.getValue();

        java.util.UUID taskId = java.util.UUID.randomUUID();
        String body = "{\"serviceTaskId\":\"" + taskId + "\",\"job\":\"taskA\",\"variables\":{}}";
        org.springframework.amqp.core.MessageProperties props =
                new org.springframework.amqp.core.MessageProperties();
        props.setCorrelationId("rel36-wiring");
        org.springframework.amqp.core.Message message =
                new org.springframework.amqp.core.Message(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), props);

        com.zorrodev.bpm.exchange.ProcessVariable v = new com.zorrodev.bpm.exchange.ProcessVariable();
        v.setName("x");
        v.setValue("1");
        v.setType("STRING");
        when(handlerA.handleJob(any())).thenReturn(java.util.List.of(v));
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpException("broker down"))
            .when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class));

        // Проброс наружу (контейнер NACK'ает вход), а не тихий success.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> listener.onMessage(message)))
                .isInstanceOf(org.springframework.amqp.AmqpException.class);
    }
}
