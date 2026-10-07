package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-66 (A): постоянный redeclare топологии воркера на каждом новом
 * соединении — через настоящий {@code HandlerAutoConfiguration.init()}
 * (G-N: объявляет прод-код, а не копия его вызовов).
 */
@ExtendWith(MockitoExtension.class)
class WorkerTopologyRedeclareTest {

    private static final String JOB = "rel66w";
    private static final String QUEUE = "zorrobpm.jobs." + JOB;

    @Mock private ApplicationContext applicationContext;
    @Mock private SimpleRabbitListenerContainerFactory connectionFactory;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private AmqpAdmin amqpAdmin;
    @Mock private CachingConnectionFactory cachingCf;
    @Mock private SimpleMessageListenerContainer container;
    @Mock private JobHandler handler;

    private HandlerAutoConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new HandlerAutoConfiguration(
            applicationContext, connectionFactory, rabbitTemplate, amqpAdmin);
        when(handler.getJob()).thenReturn(JOB);
        when(applicationContext.getBeansOfType(JobHandler.class))
            .thenReturn(Map.of("h", handler));
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        // Только путь стартового провала (здесь не используется — init идёт по
        // happy path; lenient, иначе STRICT_STUBS роняет зелёные тесты).
        org.mockito.Mockito.lenient()
            .when(container.getConnectionFactory()).thenReturn(cachingCf);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);
        when(rabbitTemplate.getConnectionFactory()).thenReturn(cachingCf);
    }

    private List<ConnectionListener> permanentListeners() {
        ArgumentCaptor<ConnectionListener> captor =
            ArgumentCaptor.forClass(ConnectionListener.class);
        verify(cachingCf).addConnectionListener(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void reconnect_redeclaresWorkQueueAndPoisonTopology_withoutRestart() {
        configuration.init();
        List<ConnectionListener> listeners = permanentListeners();
        assertThat(listeners).as("постоянный redeclare-слушатель зарегистрирован").hasSize(1);

        listeners.get(0).onCreate(mock(Connection.class));

        // Рабочая очередь + DLQ переобъявлены (стартовый declare + раунд).
        verify(amqpAdmin, times(2)).declareQueue(
            argThat(q -> q != null && QUEUE.equals(q.getName())));
        // Poison-топология переобъявлена целиком (старт + раунд).
        verify(amqpAdmin, times(2)).declareQueue(argThat(
            q -> q != null && CompletionPoisonRetryListener.POISON_QUEUE.equals(q.getName())));
        verify(amqpAdmin, times(2)).declareQueue(argThat(
            q -> q != null && CompletionPoisonRetryListener.RETRY_DELAY_QUEUE.equals(q.getName())));
    }

    @Test
    void reconnect_immediateSecondRound_debounced() {
        configuration.init();
        List<ConnectionListener> listeners = permanentListeners();

        // catch-up (первое соединение, без метки) + первый flap (метка) +
        // второй flap подряд (в окне дебаунса).
        listeners.get(0).onCreate(mock(Connection.class));
        listeners.get(0).onCreate(mock(Connection.class));
        listeners.get(0).onCreate(mock(Connection.class));

        // Старт + catch-up + ровно один flap-раунд.
        verify(amqpAdmin, times(3)).declareQueue(
            argThat(q -> q != null && QUEUE.equals(q.getName())));
    }

    @Test
    void reconnect_legacy406Queue_pinnedAfterFirstRound() {
        configuration.init();
        // Брокер отвечает 406 на рабочую очередь (pre-REL-45 определение).
        // Один стаб на все declareQueue: 406 только на рабочую очередь,
        // остальное — успех (default-null поведение мока тем же эффектом).
        when(amqpAdmin.declareQueue(any(Queue.class))).thenAnswer(inv -> {
            Queue q = inv.getArgument(0);
            if (q != null && QUEUE.equals(q.getName())) {
                throw new RuntimeException(
                    "PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'");
            }
            return q == null ? null : q.getName();
        });
        List<ConnectionListener> listeners = permanentListeners();

        listeners.get(0).onCreate(mock(Connection.class));
        listeners.get(0).onCreate(mock(Connection.class));

        // Старт (1, ещё без 406-стаба) + первый раунд (пин) + второй раунд скипнут.
        verify(amqpAdmin, times(2)).declareQueue(
            argThat(q -> q != null && QUEUE.equals(q.getName())));
    }

}
