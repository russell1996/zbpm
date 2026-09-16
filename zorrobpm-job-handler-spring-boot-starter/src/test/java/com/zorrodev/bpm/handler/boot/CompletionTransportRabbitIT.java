package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-36 критерий 1: РЕАЛЬНЫЙ broker (не мок).
 *
 * <p>Сценарий: handler уже отработал (бизнес-эффект совершён), а ПЕРЕД публикацией
 * completion рвём транспорт (destroy соединений фабрики listener-контейнера —
 * аналог обрыва сети после handler'а, до publish). Первый send падает, вход НЕ
 * подтверждается (проброс из listener'а → контейнер NACK'ает), сообщение
 * возвращается в очередь; после восстановления связи redelivery переотправляет
 * completion из result-cache — handler при этом НЕ вызывается повторно
 * (бизнес-эффект ровно один раз), а движок получает результат.
 *
 * <p>Контроль malformed (критерий 2): битый payload тихо, без отправки, без throw.
 *
 * <p>Запуск: {@code ci/run-rabbit-tests.sh} (брокер rabbitmq:3.13, host-порт через
 * {@code RABBIT_PORT}); переменные окружения читаются так же, как в
 * {@code RabbitOutboxConfirmIT} (P-23).
 */
@Tag("rabbit")
class CompletionTransportRabbitIT {

    private static final String JOB_QUEUE = "rel36.it.jobs.probe";
    private static final String COMPLETE_QUEUE = "rel36.it.complete.probe";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory listenerCf;
    private CachingConnectionFactory senderCf;
    // WO-REL-36: send-фабрика ОТДЕЛЬНАЯ от consume-фабрики — только так разрыв
    // send-пути дискриминирует swallow-vs-throw (доказано байткодом + прогоном:
    // при общей фабрике destroy убивает и consumer-канал — ACK падает вместе с
    // send, брокер requeue'ит в обоих случаях, мутант GREEN). Код listener'а тот же
    // (он просто пользуется инжектированным template), топология — scaffolding теста.
    private CachingConnectionFactory sendCf;
    private RabbitAdmin admin;
    private SimpleRabbitListenerContainerFactory containerFactory;
    private RabbitTemplate senderTemplate;
    private ObjectMapper objectMapper;

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

        listenerCf = new CachingConnectionFactory(host, port);
        listenerCf.setUsername(user);
        listenerCf.setPassword(password);
        senderCf = new CachingConnectionFactory(host, port);
        senderCf.setUsername(user);
        senderCf.setPassword(password);
        sendCf = new CachingConnectionFactory(host, port);
        sendCf.setUsername(user);
        sendCf.setPassword(password);

        admin = new RabbitAdmin(senderCf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        // Durable очереди: переживают destroy соединений (тест рвёт транспорт, не брокер).
        // Auto-delete здесь — та же ошибка, что чиним: очередь исчезала вместе с консьюмером.
        admin.declareQueue(new org.springframework.amqp.core.Queue(JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(COMPLETE_QUEUE, true, false, false));
        admin.purgeQueue(JOB_QUEUE, false);
        admin.purgeQueue(COMPLETE_QUEUE, false);
        // Fail-fast на грязный брокер: stale-сообщения дали бы ложный GREEN по счётчику
        // (content-check ниже их всё равно отбросит, но чище стартовать с нуля).
        try {
            assertThat(serverMessageCount(JOB_QUEUE)).as("вход пуст на старте").isZero();
            assertThat(serverMessageCount(COMPLETE_QUEUE)).as("completion пуст на старте").isZero();
        } catch (AssertionError e) {
            throw new IllegalStateException("stale messages on broker, purge failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable in setUp", e);
        }

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(listenerCf);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        containerFactory.setMessageConverter(converter);

        senderTemplate = new RabbitTemplate(senderCf);
        senderTemplate.setMessageConverter(converter);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        try {
            admin.purgeQueue(JOB_QUEUE, false);
            admin.purgeQueue(COMPLETE_QUEUE, false);
        } catch (Exception ignored) {
        }
        try {
            senderCf.destroy();
        } catch (Exception ignored) {
        }
        try {
            sendCf.destroy();
        } catch (Exception ignored) {
        }
        try {
            listenerCf.destroy();
        } catch (Exception ignored) {
        }
    }

    private static JobDetailModel jobDetail(UUID serviceTaskId) {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob("probe");
        detail.setVariables(Map.of());
        return detail;
    }

    private static void await(String what, Duration timeout, java.util.function.BooleanSupplier cond)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    @Test
    void brokenConnectionBeforeCompletionPublish_resultDeliveredAfterRecovery_effectOnce()
            throws Exception {
        UUID taskId = UUID.randomUUID();
        String correlationId = "rel36-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);
        CountDownLatch handlerDone = new CountDownLatch(1);
        // Handler НЕ возвращается, пока тест не разрешит: разрыв транспорта происходит
        // строго ПОСЛЕ совершённого бизнес-эффекта и строго ДО publish completion —
        // без этой координации тест гонялся бы с реальной гонкой send-vs-destroy.
        CountDownLatch releaseWork = new CountDownLatch(1);

        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "probe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                handlerCalls.incrementAndGet();
                handlerDone.countDown();
                try {
                    if (!releaseWork.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("тест не отпустил работу вовремя");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                com.zorrodev.bpm.exchange.ProcessVariable v =
                    new com.zorrodev.bpm.exchange.ProcessVariable();
                v.setName("x");
                v.setValue("1");
                v.setType("STRING");
                return List.of(v);
            }
        };

        // RabbitTemplate listener'а — на ОТДЕЛЬНОЙ send-фабрике (см. поле sendCf):
        // разрываем ТОЛЬКО send-путь; consumer-канал жив — swallow-vs-throw
        // дискриминируется: swallow → ACK живого канала → потеря; throw → NACK → requeue.
        RabbitTemplate listenerTemplate = new RabbitTemplate(sendCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            new JobCompletionListener(handler, listenerTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);

        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container =
            containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            // Вход ОБЪЕКТОМ через прод-конвертер (как engine шлёт JobDetailModel
            // в ServiceTaskListener): correlationId ставит отправитель (как outboxId).
            senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
                m.getMessageProperties().setCorrelationId(correlationId);
                return m;
            });

            // Ждём: бизнес-эффект совершён (handler внутри, держит latch).
            assertThat(handlerDone.await(20, TimeUnit.SECONDS)).as("handler отработал").isTrue();

            // РВЁМ ТОЛЬКО send-транспорт после handler'а, до publish completion:
            // destroy(sendCf) закрывает его соединения/каналы + setPort(9) (discard) —
            // даже пересозданное соединение упрётся в connection-refused. Consumer-фабрика
            // (listenerCf) жива — её ACK/NACK-путь работает, и в этом весь POF-смысл:
            // при старом swallow вход подтверждается живым каналом (потеря результата),
            // при новом throw — NACK и requeue (результат в resultCache, не потерян).
            sendCf.destroy();
            sendCf.setPort(9);
            // Отпускаем handler: он возвращается → listener шлёт completion → send падает →
            // проброс наружу → вход НЕ подтверждается (NACK живого канала),
            // результат уже лежит в resultCache.
            releaseWork.countDown();

            // Первый send упал → проброс → requeue живого канала. Контейнер
            // мгновенно redeliver'ит: handler НЕ повторяется (кэш!), send снова
            // падает — цикл продолжается, пока send-транспорт мёртв. Здесь НЕ
            // проверяем ready-count очереди (во время hot-loop сообщение почти
            // всегда unacked — ready=0, проверка была бы ложно-красной): даём
            // циклу покрутиться, фиксируем лишь однократность эффекта.
            // Дискриминатор swallow-vs-throw — ФИНАЛЬНЫЙ completion с content-check
            // ниже: при старом swallow вход подтверждён и после recovery нечего
            // переотправлять (таймаут), при новом throw — completion приходит.
            Thread.sleep(3000);
            assertThat(handlerCalls.get())
                .as("эффект ровно один раз (все redelivery идут из кэша)")
                .isEqualTo(1);

            // ВОССТАНАВЛИВАЕМ send-связь: свежая фабрика вместо убитой sendCf.
            // Consumer-контейнер всё это время ЖИВ (его фабрика не рвалась) — новый
            // контейнер не нужен; result-cache живёт в jobListener. Redelivery с живого
            // входа переотправляет completion из кэша, handler не повторяется.
            CachingConnectionFactory freshCf = new CachingConnectionFactory(host, port);
            freshCf.setUsername(user);
            freshCf.setPassword(password);
            try {
                RabbitTemplate freshTemplate = new RabbitTemplate(freshCf);
                freshTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
                // Переподключаем listener на свежий send-транспорт тем же объектом
                // (кэш результата при нём).
                java.lang.reflect.Field tf =
                    JobCompletionListener.class.getDeclaredField("rabbitTemplate");
                tf.setAccessible(true);
                tf.set(jobListener, freshTemplate);

                // Redelivery уже в очереди (NACK живого канала): completion доставлен
                // движку из кэша, handler не повторён. Content-check: именно наш taskId
                // со статусом SUCCESS (счётчик ловил бы и чужой stale).
                awaitCompletionForTask(taskId, Duration.ofSeconds(20));
                // Даём redelivery докатиться, затем проверяем однократность эффекта.
                Thread.sleep(2000);
                assertThat(handlerCalls.get())
                    .as("бизнес-эффект ровно один раз (переотправка — только completion)")
                    .isEqualTo(1);
                // Очередь completion'ов после consumption пуста — ничего чужого не породили.
                assertThat(serverMessageCount(COMPLETE_QUEUE))
                    .as("completion потреблён наблюдателем, мусора нет")
                    .isZero();
                freshCf.destroy();
            } catch (Exception e) {
                freshCf.destroy();
                throw e;
            }
        } finally {
            container.stop();
        }
    }

    @Test
    void malformedPayload_quietNoSend_realBroker() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger(0);
        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "probe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                handlerCalls.incrementAndGet();
                return List.of();
            }
        };
        RabbitTemplate listenerTemplate = new RabbitTemplate(listenerCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            new JobCompletionListener(handler, listenerTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);

        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container =
            containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            senderTemplate.convertAndSend(JOB_QUEUE, (Object) "not-json{{{");
            Thread.sleep(3000);
            assertThat(handlerCalls.get()).as("malformed не доходит до handler'а").isZero();
            assertThat(serverMessageCount(COMPLETE_QUEUE))
                .as("malformed не порождает completion").isZero();
        } finally {
            container.stop();
        }
    }

    /** Очередь через raw AMQP (без кэшированных фабрик — независимый наблюдатель). */
    private int serverMessageCount(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        }
    }

    /**
     * Ждёт completion именно НАШЕГО taskId (content-check, не счётчик).
     * Счётчик сообщений не дискриминирует: stale-сообщение чужого прогона тоже
     * даёт count>=1 (ложный GREEN), а во время hot-loop redelivery ready-count
     * почти всегда 0 (сообщение unacked) — ложный RED. Контент ловит оба случая.
     * Чужие сообщения ack'аются и отбрасываются (очереди приватны тесту).
     */
    private void awaitCompletionForTask(UUID taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            while (System.nanoTime() < deadline) {
                com.rabbitmq.client.GetResponse resp =
                    ch.basicGet(COMPLETE_QUEUE, true);
                if (resp != null) {
                    String body = new String(resp.getBody(), StandardCharsets.UTF_8);
                    try {
                        com.zorrodev.bpm.exchange.ServiceTaskCompleteData data =
                            objectMapper.readValue(body,
                                com.zorrodev.bpm.exchange.ServiceTaskCompleteData.class);
                        if (taskId.equals(data.getServiceTaskId())
                            && "SUCCESS".equals(data.getStatus())) {
                            return;
                        }
                    } catch (Exception ignored) {
                        // Чужой мусор — отброшен ack'ом выше, ждём дальше.
                    }
                } else {
                    Thread.sleep(200);
                }
            }
        }
        throw new IllegalStateException(
            "timed out waiting for completion of task " + taskId);
    }

    /** Прямой вызов listener'а для отладки ветвлений без брокера (не критерий 1). */
    @Test
    void directListener_smokeBranches() {
        MessageProperties props = new MessageProperties();
        Message malformed = new Message("{{{".getBytes(StandardCharsets.UTF_8), props);
        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "probe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                return List.of();
            }
        };
        RabbitTemplate tpl = new RabbitTemplate(listenerCf);
        MessageListener l = new JobCompletionListener(handler, tpl, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);
        l.onMessage(malformed);
    }
}
