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
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
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
import static org.mockito.Mockito.never;
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
    private CachingConnectionFactory cachingConnectionFactory;

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

        // Two containers created (one per handler) + WO-REL-64 poison-retry container
        verify(connectionFactory, times(3)).createListenerContainer();
        verify(container, times(3)).start();
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

        // Then: job queue not declared (already exists); WO-REL-64 poison
        // topology still declared (new queues, unrelated to the job queue).
        verify(amqpAdmin, times(0)).declareQueue(
            argThat(q -> q != null && q.getName().equals("zorrobpm.jobs.taskA")));
        verify(amqpAdmin, times(1)).declareQueue(
            argThat(q -> q != null
                && q.getName().equals(CompletionPoisonRetryListener.POISON_QUEUE)));
        verify(amqpAdmin, times(1)).declareQueue(
            argThat(q -> q != null
                && q.getName().equals(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE)));
    }

    @Test
    @DisplayName("No handlers found — no job queues or containers created (poison-retry still starts)")
    void noHandlers() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of());
        when(connectionFactory.createListenerContainer()).thenReturn(container);

        // When
        configuration.init();

        // Then: no job containers created — but WO-REL-64 poison-retry container
        // starts anyway (any live worker drains poison parked by others).
        verify(connectionFactory, times(1)).setMessageConverter(any());
        verify(rabbitTemplate, times(1)).setMessageConverter(any());
        verify(connectionFactory, times(1)).createListenerContainer();
        verify(container).setQueueNames(CompletionPoisonRetryListener.POISON_QUEUE);
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
        // WO-REL-64: контейнеров два (рабочий + poison-повтор) на одном моке —
        // забираем именно рабочий слушатель, а не первый попавшийся.
        verify(container, times(2)).setMessageListener(listenerCaptor.capture());
        assertThat(listenerCaptor.getAllValues())
            .filteredOn(JobCompletionListener.class::isInstance)
            .hasSize(1);
    }

    @Test
    @DisplayName("WO-REL-64: poison-retry container subscribes the poison queue with its own listener")
    void poisonRetryListenerSubscribed() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        configuration.init();

        // Топология яда объявлена настоящим declare (не копией аргументов —
        // G-N: убери declarePoisonTopology из init, и тест красный).
        verify(amqpAdmin).declareQueue(argThat(q -> q != null
            && q.getName().equals(CompletionPoisonRetryListener.POISON_QUEUE)));
        verify(amqpAdmin).declareQueue(argThat(q -> q != null
            && q.getName().equals(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE)));

        ArgumentCaptor<String> queueNames = ArgumentCaptor.forClass(String.class);
        verify(container, times(2)).setQueueNames(queueNames.capture());
        assertThat(queueNames.getAllValues()).containsExactlyInAnyOrder(
            "zorrobpm.jobs.taskA", CompletionPoisonRetryListener.POISON_QUEUE);

        ArgumentCaptor<org.springframework.amqp.core.MessageListener> listenerCaptor =
                ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container, times(2)).setMessageListener(listenerCaptor.capture());
        assertThat(listenerCaptor.getAllValues())
            .filteredOn(CompletionPoisonRetryListener.class::isInstance)
            .hasSize(1);
        verify(container, times(2)).start();
    }

    @Test
    @DisplayName("WO-REL-64: poison topology and listener absent when parking is disabled")
    void noPoisonTopologyWhenDisabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            configuration, "completionPoisonEnabled", false);
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);

        configuration.init();

        verify(amqpAdmin, never()).declareQueue(argThat(q -> q != null
            && q.getName().equals(CompletionPoisonRetryListener.POISON_QUEUE)));
        verify(amqpAdmin, never()).declareQueue(argThat(q -> q != null
            && q.getName().equals(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE)));
        ArgumentCaptor<org.springframework.amqp.core.MessageListener> listenerCaptor =
                ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container, times(1)).setMessageListener(listenerCaptor.capture());
        assertThat(listenerCaptor.getAllValues())
            .filteredOn(CompletionPoisonRetryListener.class::isInstance)
            .isEmpty();
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
        // WO-REL-64: контейнеров два — забираем рабочий слушатель из всех.
        verify(container, times(2)).setMessageListener(listenerCaptor.capture());
        org.springframework.amqp.core.MessageListener listener = listenerCaptor.getAllValues()
            .stream()
            .filter(JobCompletionListener.class::isInstance)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no JobCompletionListener subscribed"));

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
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(org.springframework.amqp.rabbit.connection.CorrelationData.class));

        // Проброс наружу (контейнер NACK'ает вход), а не тихий success.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> listener.onMessage(message)))
                .isInstanceOf(org.springframework.amqp.AmqpException.class);
    }

    @Test
    @DisplayName("WO-REL-62 (бывший brokerUnreachableAtStartup_skipsSubscription): "
        + "брокер недоступен на старте — контейнер СТАРТУЕТ (reconnect-цикл), "
        + "declare переносится на переподключение; встроенный и внешний воркеры одинаково")
    void brokerUnreachableAtStartup_startsContainerAndDeclaresOnReconnect() {
        // Встроенный zorrobpm:http + внешний воркер — один и тот же стартер-код (критерий 4).
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA, "handlerB", handlerB));
        when(handlerA.getJob()).thenReturn("zorrobpm:http");
        when(handlerB.getJob()).thenReturn("ext-billing");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(container.getConnectionFactory()).thenReturn(cachingConnectionFactory);
        // Брокер недоступен: getQueueInfo бросает, а не возвращает null.
        when(amqpAdmin.getQueueInfo(anyString()))
                .thenThrow(new org.springframework.amqp.AmqpConnectException(
                    new java.net.ConnectException("Connection refused")));

        // When: init НЕ бросает — приложение стартует без брокера (критерий 1: не "healthy и глухой").
        configuration.init();

        // Then: ОБА рабочих контейнера созданы, привязаны и СТАРТОВАНЫ (reconnect-цикл
        // подхватит брокер) + WO-REL-64 poison-контейнер (ядро очереди течёт, повтор отделён).
        ArgumentCaptor<String> queueNames = ArgumentCaptor.forClass(String.class);
        verify(container, times(3)).setQueueNames(queueNames.capture());
        assertThat(queueNames.getAllValues()).containsExactlyInAnyOrder(
            "zorrobpm.jobs.zorrobpm:http", "zorrobpm.jobs.ext-billing",
            CompletionPoisonRetryListener.POISON_QUEUE);
        ArgumentCaptor<org.springframework.amqp.core.MessageListener> listenerCaptor =
                ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container, times(3)).setMessageListener(listenerCaptor.capture());
        assertThat(listenerCaptor.getAllValues())
            .filteredOn(JobCompletionListener.class::isInstance)
            .hasSize(2);
        assertThat(listenerCaptor.getAllValues())
            .filteredOn(CompletionPoisonRetryListener.class::isInstance)
            .hasSize(1);
        // Очередь может появиться позже (declare на reconnect) — контейнер не умирает, а ждёт.
        verify(container, times(3)).setMissingQueuesFatal(false);
        verify(container, times(3)).start();
        // Стартовых РАБОЧИХ declare'ов не было (брокер лежал), но redeclare
        // запланирован на reconnect. WO-REL-64: poison-топология declare'ится
        // ПРЯМО (не per-handler): брокер лежал и тут — declare тоже отложен.
        verify(amqpAdmin, times(0)).declareQueue(
            argThat(q -> q != null && q.getName().startsWith("zorrobpm.jobs.")));
        ArgumentCaptor<ConnectionListener> reconnectCaptor =
                ArgumentCaptor.forClass(ConnectionListener.class);
        verify(cachingConnectionFactory, times(2)).addConnectionListener(reconnectCaptor.capture());

        // When: брокер вернулся (новое физическое соединение) — declare срабатывает без рестарта.
        // (onCreate объявляет безусловно, без getQueueInfo-check — пере-заглушка не нужна.)
        for (ConnectionListener l : reconnectCaptor.getAllValues()) {
            l.onCreate(mock(Connection.class));
        }

        // Then: обе очереди объявлены (рабочая + DLQ на каждую — см. existingQueueNotRedeclared-счёт).
        verify(amqpAdmin).declareQueue(argThat(q -> q.getName().equals("zorrobpm.jobs.zorrobpm:http")));
        verify(amqpAdmin).declareQueue(argThat(q -> q.getName().equals("zorrobpm.jobs.ext-billing")));
        // Успешный declare снимает одноразовый слушатель.
        verify(cachingConnectionFactory, times(2)).removeConnectionListener(any(ConnectionListener.class));
    }

    @Test
    @DisplayName("WO-REL-62: declare упал на старте (брокер умер между check и declare) — "
        + "контейнер всё равно стартует, повтор запланирован на reconnect")
    void startupDeclareFailure_stillStartsContainerAndSchedulesRetry() {
        when(applicationContext.getBeansOfType(JobHandler.class))
                .thenReturn(Map.of("handlerA", handlerA));
        when(handlerA.getJob()).thenReturn("taskA");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(container.getConnectionFactory()).thenReturn(cachingConnectionFactory);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);
        // Брокер умер посреди стартового declare: падает именно DLQ-declare
        // (первый declareQueue в цепочке), до рабочей очереди старт не доходит.
        // declareQueue НЕ void (возвращает имя очереди) — doNothing() неприменим.
        org.mockito.Mockito.doThrow(new RuntimeException("broker died mid-declare"))
                .doReturn("zorrobpm.jobs.taskA.dlq")
                .when(amqpAdmin).declareQueue(argThat(
                    q -> q != null && q.getName().equals("zorrobpm.jobs.taskA.dlq")));

        // When: init НЕ бросает.
        configuration.init();

        // Then: контейнеры стартованы (рабочий + WO-REL-64 poison: яд-топология
        // declare'ится прямо, брокер на неё не умирал), redeclare запланирован.
        verify(container, times(2)).start();
        ArgumentCaptor<ConnectionListener> reconnectCaptor =
                ArgumentCaptor.forClass(ConnectionListener.class);
        verify(cachingConnectionFactory, times(1)).addConnectionListener(reconnectCaptor.capture());

        // When: reconnect — declare проходит, слушатель снимается.
        reconnectCaptor.getValue().onCreate(mock(Connection.class));

        // Then: старт внёс 0 рабочих declare (умер на DLQ), reconnect — ровно 1 полный
        // declare (DLQ + рабочая). DLQ суммарно: 1 падение + 1 успех.
        verify(amqpAdmin, times(1)).declareQueue(
            argThat(q -> q.getName().equals("zorrobpm.jobs.taskA")));
        verify(amqpAdmin, times(2)).declareQueue(
            argThat(q -> q.getName().equals("zorrobpm.jobs.taskA.dlq")));
        verify(cachingConnectionFactory, times(1)).removeConnectionListener(any(ConnectionListener.class));
    }
}
