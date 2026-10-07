package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-REL-66 критерий 2: топология REL-64 (poison/TTL-DLX) восстанавливается
 * после потери на брокере — без рестарта воркера.
 *
 * <p>На master {@code declarePoisonTopology} вызывается из {@code init()} один
 * раз; переподключение (как после пересоздания брокера в инциденте 2026-10-07)
 * топологию не возвращает — слушатель {@code zorrobpm.completion.poison}
 * получает {@code not_found}.
 *
 * <p>Таблица: удалены {@code zorrobpm.completion.poison},
 * {@code zorrobpm.completion.retry-delay} и рабочая очередь хендлера →
 * сброс соединения → все три возвращаются. На master КРАСНЫЙ.
 * Запуск: {@code ci/run-rabbit-tests.sh} (свой брокер).
 */
@Tag("rabbit")
class PoisonTopologySelfHealRabbitIT {

    private static final String JOB = "rel66poison" + UUID.randomUUID().toString().substring(0, 8);
    private static final String QUEUE = "zorrobpm.jobs." + JOB;

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory workerCf;
    private CachingConnectionFactory liveCf;
    private RabbitAdmin liveAdmin;

    private final List<SimpleMessageListenerContainer> startedContainers =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        liveCf = new CachingConnectionFactory(host, port);
        liveCf.setUsername(user);
        liveCf.setPassword(password);
        liveAdmin = new RabbitAdmin(liveCf);
        liveAdmin.setAutoStartup(true);
        liveAdmin.afterPropertiesSet();
        try {
            liveAdmin.deleteQueue(QUEUE);
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable in setUp", e);
        }

        workerCf = new CachingConnectionFactory(host, port);
        workerCf.setUsername(user);
        workerCf.setPassword(password);
    }

    @AfterEach
    void tearDown() {
        for (SimpleMessageListenerContainer c : startedContainers) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
        }
        startedContainers.clear();
        try {
            workerCf.destroy();
        } catch (Exception ignored) {
        }
        try {
            liveAdmin.deleteQueue(QUEUE);
            liveAdmin.deleteQueue(QUEUE + ".dlq");
        } catch (Exception ignored) {
        }
        try {
            liveCf.destroy();
        } catch (Exception ignored) {
        }
    }

    @Test
    void reconnectAfterTopologyLoss_redeclaresPoisonDelayAndWorkQueues() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.getJob()).thenReturn(JOB);
        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBeansOfType(JobHandler.class)).thenReturn(Map.of("h", handler));

        SimpleRabbitListenerContainerFactory containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(workerCf);
        containerFactory.setMessageConverter(
            new Jackson2JsonMessageConverter(new ObjectMapper()));
        SimpleRabbitListenerContainerFactory spyFactory = Mockito.spy(containerFactory);
        Mockito.doAnswer(inv -> {
            SimpleMessageListenerContainer c =
                (SimpleMessageListenerContainer) inv.callRealMethod();
            startedContainers.add(c);
            return c;
        }).when(spyFactory).createListenerContainer();

        RabbitAdmin workerAdmin = new RabbitAdmin(workerCf);
        RabbitTemplate workerTemplate = new RabbitTemplate(workerCf);

        // Живой init() настоящего прод-класса (G-N): объявляет всю топологию.
        new HandlerAutoConfiguration(mockCtx, spyFactory, workerTemplate, workerAdmin).init();

        String poison = CompletionPoisonRetryListener.POISON_QUEUE;
        String delay = CompletionPoisonRetryListener.RETRY_DELAY_QUEUE;
        await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(poison));
        await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(delay));
        await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(QUEUE));

        // Инцидент: топология потеряна на брокере.
        liveAdmin.deleteQueue(poison);
        liveAdmin.deleteQueue(delay);
        liveAdmin.deleteQueue(QUEUE);
        assertThat(queueExists(poison)).as("poison удалён").isFalse();

        // Новое физическое соединение (как после пересоздания брокера).
        workerCf.resetConnection();
        workerCf.createConnection();

        // WO-REL-66: вся топология возвращается без рестарта воркера.
        await().atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(queueExists(poison))
                .as("poison-очередь переобъявлена на reconnect")
                .isTrue());
        await().atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(queueExists(delay))
                .as("delay-очередь переобъявлена на reconnect")
                .isTrue());
        await().atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(queueExists(QUEUE))
                .as("рабочая очередь переобъявлена на reconnect")
                .isTrue());
    }

    /** Независимый наблюдатель: passive declare через raw AMQP (мимо кэшей фабрик). */
    private boolean queueExists(String queue) {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            try {
                ch.queueDeclarePassive(queue);
                return true;
            } catch (IOException e) {
                return false;
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return false;
            }
            throw new IllegalStateException("queueExists probe failed", e);
        }
    }
}
