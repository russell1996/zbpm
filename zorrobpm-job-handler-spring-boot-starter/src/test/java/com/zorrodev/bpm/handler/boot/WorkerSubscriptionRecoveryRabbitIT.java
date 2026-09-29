package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-REL-62 критерий 2: РЕАЛЬНЫЙ broker (не мок), РЕАЛЬНЫЙ даун (не spy-check).
 *
 * <p>Сценарий: worker-JVM стартует, когда брокер НЕДОСТУПЕН (её
 * {@code CachingConnectionFactory} смотрит в мёртвый порт — настоящий
 * {@code AmqpConnectException} из живого {@code RabbitAdmin}, без моков на
 * admin-пути) → {@code HandlerAutoConfiguration.init()} НЕ бросает и НЕ
 * пропускает подписку: контейнер создан и стартован, крутится в собственном
 * reconnect-цикле → "брокер возвращается" (порт переключается на живой брокер —
 * та же фабрика, тот же процесс, БЕЗ рестарта) → recovery-цикл фабрики
 * переподключается сам → {@code onCreate} объявляет очередь → отправленная
 * задача реально обрабатывается.
 *
 * <p>На старом коде (WO-ENG-31, {@code continue} в catch) этот тест КРАСНЫЙ:
 * контейнер не создаётся вообще — после "возвращения" брокера очередь никто не
 * объявляет и не слушает, финальный latch висит до таймаута (доказано
 * POF-прогоном: {@code handled.await} → false через 20с).
 *
 * <p>Запуск: {@code ci/run-rabbit-tests.sh} (брокер rabbitmq:4.1, переменные
 * окружения как в {@code CompletionTransportRabbitIT}, P-23).
 */
@Tag("rabbit")
class WorkerSubscriptionRecoveryRabbitIT {

    private static final String JOB = "rel62probe";
    private static final String QUEUE = "zorrobpm.jobs." + JOB;
    private static final String DLQ = QUEUE + ".dlq";

    /** Заведомо мёртвый порт (discard): эмулирует лежащий брокер. */
    private static final int DEAD_PORT = 9;

    private String host;
    private int port;
    private String user;
    private String password;

    /** Живая фабрика — только для подготовки/уборки брокера и отправки задач. */
    private CachingConnectionFactory liveCf;
    private RabbitAdmin liveAdmin;
    private RabbitTemplate senderTemplate;

    /** Фабрика worker-JVM: стартует против DEAD_PORT, потом переключается на живой. */
    private CachingConnectionFactory workerCf;
    private SimpleRabbitListenerContainerFactory containerFactory;

    private final List<SimpleMessageListenerContainer> startedContainers =
        new CopyOnWriteArrayList<>();

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
        // Fail-fast на грязный/неживой брокер харнесса (не на код задачи).
        try {
            liveAdmin.deleteQueue(QUEUE);
            liveAdmin.deleteQueue(DLQ);
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable in setUp", e);
        }

        senderTemplate = new RabbitTemplate(liveCf);
        senderTemplate.setMessageConverter(
            new Jackson2JsonMessageConverter(new ObjectMapper()));

        workerCf = new CachingConnectionFactory(host, DEAD_PORT);
        workerCf.setUsername(user);
        workerCf.setPassword(password);

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(workerCf);
        containerFactory.setMessageConverter(
            new Jackson2JsonMessageConverter(new ObjectMapper()));
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
            liveAdmin.deleteQueue(DLQ);
        } catch (Exception ignored) {
        }
        try {
            liveCf.destroy();
        } catch (Exception ignored) {
        }
    }

    @Test
    void brokerDownAtStartup_recoversOnReconnectWithoutRestart() throws Exception {
        UUID taskId = UUID.randomUUID();
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<UUID> seenTaskId = new AtomicReference<>();
        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return JOB;
            }

            @Override
            public List<ProcessVariable> handleJob(JobDetailModel model) {
                seenTaskId.set(model.getServiceTaskId());
                handled.countDown();
                ProcessVariable v = new ProcessVariable();
                v.setName("x");
                v.setValue("1");
                v.setType("STRING");
                return List.of(v);
            }
        };
        ApplicationContext mockCtx = mock(ApplicationContext.class);
        when(mockCtx.getBeansOfType(JobHandler.class)).thenReturn(Map.of("h", handler));

        // Spy фабрики: только наблюдаем созданные контейнеры (stop в tearDown),
        // поведение — настоящий prod-путь. Admin — ЖИВОЙ RabbitAdmin на workerCf:
        // getQueueInfo падает настоящим AmqpConnectException (брокер реально
        // недоступен — порт мёртв), declare'ы реально ждут переподключения.
        RabbitAdmin workerAdmin = new RabbitAdmin(workerCf);
        SimpleRabbitListenerContainerFactory spyFactory = Mockito.spy(containerFactory);
        Mockito.doAnswer(inv -> {
            SimpleMessageListenerContainer c =
                (SimpleMessageListenerContainer) inv.callRealMethod();
            startedContainers.add(c);
            return c;
        }).when(spyFactory).createListenerContainer();
        RabbitTemplate workerTemplate = new RabbitTemplate(workerCf);

        // When: старт при ЛЕЖАЩЕМ брокере — init НЕ бросает (app живёт).
        new HandlerAutoConfiguration(mockCtx, spyFactory, workerTemplate, workerAdmin).init();

        // Then: подписка НЕ пропущена — контейнер создан и стартован, крутится
        // в recovery-цикле (критерий 1: не "healthy и глухой").
        assertThat(startedContainers).as("контейнер создан при недоступном брокере").hasSize(1);
        assertThat(startedContainers.get(0).isRunning())
            .as("контейнер стартован (ждёт брокер/очередь, не глух)")
            .isTrue();
        // Очереди реально нет: стартовый declare не дошёл (брокер лежал).
        assertThat(queueExists(QUEUE)).as("очередь отсутствует до возвращения брокера").isFalse();

        // When: "брокер возвращается" — ТА ЖЕ фабрика, ТОТ ЖЕ процесс, без рестарта:
        // порт переключается на живой брокер, мёртвые соединения сбрасываются —
        // дальше recovery-цикл фабрики/контейнера работает САМ (форсированного
        // createConnection нет — самовосстановление доказывается, а не имитируется).
        workerCf.setPort(port);
        workerCf.destroy();

        // Then: очередь объявлена на reconnect без рестарта процесса.
        await("очередь объявлена на reconnect без рестарта")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .until(() -> queueExists(QUEUE));
        assertThat(queueExists(DLQ)).as("DLQ объявлена вместе с рабочей очередью").isTrue();

        // When: задача отправлена в очередь (как движок через send-путь).
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(taskId);
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob(JOB);
        detail.setVariables(Map.of());
        senderTemplate.convertAndSend(QUEUE, detail);

        // Then: handler реально обработал — КОНКРЕТНЫЙ taskId (P-67), без рестарта.
        assertThat(handled.await(20, TimeUnit.SECONDS))
            .as("задача обработана после возвращения брокера, без ручного рестарта")
            .isTrue();
        assertThat(seenTaskId.get()).as("обработана именно наша задача").isEqualTo(taskId);
    }

    /** Независимый наблюдатель: passive declare через raw AMQP (мимо кэшей фабрик). */
    private boolean queueExists(String queue) {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        // 404 на несуществующую очередь закрывает КАНАЛ (не соединение) через
        // AlreadyClosedException из channel.close() — это и есть сигнал "нет".
        // Ловим всё с текстом 404 здесь, а не в try-with-resources: исключение
        // вылетает из close(), не из тела try.
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
