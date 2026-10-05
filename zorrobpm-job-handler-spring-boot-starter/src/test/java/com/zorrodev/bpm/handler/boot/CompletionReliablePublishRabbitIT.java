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
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-C8-36 (CR-13, крит.4–5): надёжная публикация completion на живом брокере.
 *
 * <p>Крит.4: немаршрутизируемый результат НЕ считается успешной обработкой —
 * вход не подтверждается (redelivery hot-loop из resultCache, бизнес-эффект
 * один раз); после правки маршрута («объявили очередь») результат применяется
 * РОВНО ОДИН раз (content-check нашего taskId).
 *
 * <p>Крит.5: разрыв confirm после отправки (первый wait падает — эмуляция
 * NACK/timeout/разрыва до confirm на живом брокере) НЕ оставляет подтверждённое
 * задание без восстанавливаемого результата: вход NACK'ается, redelivery
 * переотправляет из кэша, handler один раз.
 *
 * <p>POF-контроль обоих: на старом коде (без confirm-wait) те же сценарии дают
 * ACK-потерю — первый тест висит до таймаута (результат «ушёл в пустоту»,
 * переотправлять нечего), второй — не фиксирует потерю confirm (wait вообще не
 * зовётся). Проверено откатом прод-файлов (см. отчёт).
 */
@Tag("rabbit")
class CompletionReliablePublishRabbitIT {

    private static final String JOB_QUEUE = "c836.it.jobs.probe";
    private static final String COMPLETE_QUEUE = "c836.it.complete.probe";
    private static final String NO_SUCH_QUEUE = "c836.it.complete.unroutable";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory listenerCf;
    private CachingConnectionFactory sendCf;
    private CachingConnectionFactory senderCf;
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

        listenerCf = confirmedFactory();
        senderCf = confirmedFactory();
        sendCf = confirmedFactory();

        admin = new RabbitAdmin(senderCf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new org.springframework.amqp.core.Queue(JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(COMPLETE_QUEUE, true, false, false));
        admin.purgeQueue(JOB_QUEUE, false);
        admin.purgeQueue(COMPLETE_QUEUE, false);
        assertThat(serverMessageCount(JOB_QUEUE)).as("вход пуст на старте").isZero();
        assertThat(serverMessageCount(COMPLETE_QUEUE)).as("completion пуст на старте").isZero();

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(listenerCf);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        containerFactory.setMessageConverter(converter);

        senderTemplate = new RabbitTemplate(senderCf);
        senderTemplate.setMessageConverter(converter);
        objectMapper = new ObjectMapper();
    }

    /** Фабрика как у прод-стартера после WO (CORRELATED + returns). */
    private CachingConnectionFactory confirmedFactory() {
        CachingConnectionFactory cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);
        return cf;
    }

    @AfterEach
    void tearDown() {
        try {
            admin.purgeQueue(JOB_QUEUE, false);
            admin.purgeQueue(COMPLETE_QUEUE, false);
        } catch (Exception ignored) {
        }
        for (CachingConnectionFactory cf : new CachingConnectionFactory[]{senderCf, sendCf, listenerCf}) {
            try {
                cf.destroy();
            } catch (Exception ignored) {
            }
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

    /** Listener на прод-шаблоне: mandatory + returns в общий сет (как стартер). */
    private JobCompletionListener listenerOn(RabbitTemplate template, JobHandler handler,
            String completeQueue, Set<String> returnedIds) {
        template.setMandatory(true);
        template.setReturnsCallback(returned -> {
            String cid = returned.getMessage() != null
                ? returned.getMessage().getMessageProperties().getCorrelationId()
                : null;
            if (cid != null) {
                returnedIds.add(cid);
            }
        });
        JobCompletionListener jobListener =
            new JobCompletionListener(handler, template, objectMapper, JOB_QUEUE, completeQueue);
        jobListener.setEnsurePublisherConfirms(true);
        jobListener.setConfirmTimeoutMs(5_000L);
        jobListener.setReturnedCompletionIds(returnedIds);
        return jobListener;
    }

    @Test
    void unroutableCompletion_notAcked_recoveredAfterRouteFix_appliedOnce() throws Exception {
        UUID taskId = UUID.randomUUID();
        String correlationId = "c836-c4-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);
        Set<String> returnedIds = ConcurrentHashMap.newKeySet();

        JobHandler handler = probeHandler(handlerCalls);
        RabbitTemplate listenerTemplate = new RabbitTemplate(sendCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            listenerOn(listenerTemplate, handler, NO_SUCH_QUEUE, returnedIds);

        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container =
            containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
                m.getMessageProperties().setCorrelationId(correlationId);
                return m;
            });

            // Немаршрутизируемый completion: return + throw → NACK → hot-loop
            // redelivery из кэша (эффект один раз). Тишина 3с = доказательство
            // однократности (инвертированное условие — см. REL-36).
            // WO-QW-7: намеренно Thread.sleep, не Awaitility.
            Thread.sleep(3000);
            assertThat(handlerCalls.get())
                .as("эффект ровно один раз (все redelivery — из кэша)")
                .isEqualTo(1);

            // «Правка маршрута»: объявляем очередь — следующий redelivery доходит.
            admin.declareQueue(new org.springframework.amqp.core.Queue(NO_SUCH_QUEUE, true, false, false));
            try {
                awaitCompletionFrom(taskId, NO_SUCH_QUEUE, Duration.ofSeconds(20));
                Thread.sleep(2000);
                assertThat(handlerCalls.get())
                    .as("бизнес-эффект ровно один раз (переотправка — только completion)")
                    .isEqualTo(1);
            } finally {
                admin.purgeQueue(NO_SUCH_QUEUE, false);
            }
        } finally {
            container.stop();
            try {
                admin.deleteQueue(NO_SUCH_QUEUE);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void confirmLostAfterSend_inputNotAcked_resultRecovered_effectOnce() throws Exception {
        UUID taskId = UUID.randomUUID();
        String correlationId = "c836-c5-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);
        AtomicBoolean confirmLostOnce = new AtomicBoolean(false);
        Set<String> returnedIds = ConcurrentHashMap.newKeySet();

        JobHandler handler = probeHandler(handlerCalls);
        // Живой брокер, но ПЕРВЫЙ confirm теряем (эмуляция NACK/timeout/разрыва
        // после отправки — детерминированно, без гонки send-vs-destroy).
        RabbitTemplate flakyTemplate = new RabbitTemplate(sendCf) {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override
            public void waitForConfirmsOrDie(long timeout) {
                if (first.compareAndSet(true, false)) {
                    confirmLostOnce.set(true);
                    throw new org.springframework.amqp.AmqpException(
                        "C836-IT: simulated confirm loss after send");
                }
                super.waitForConfirmsOrDie(timeout);
            }
        };
        flakyTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            listenerOn(flakyTemplate, handler, COMPLETE_QUEUE, returnedIds);

        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container =
            containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
                m.getMessageProperties().setCorrelationId(correlationId);
                return m;
            });

            // Потеря confirm → throw → NACK → redelivery из кэша → второй send
            // подтверждает → ACK. Эффект один раз, результат доставлен.
            awaitCompletionFrom(taskId, COMPLETE_QUEUE, Duration.ofSeconds(20));
            assertThat(confirmLostOnce.get())
                .as("первый confirm реально потерян (иначе сценарий пуст)")
                .isTrue();
            Thread.sleep(2000);
            assertThat(handlerCalls.get())
                .as("бизнес-эффект ровно один раз")
                .isEqualTo(1);
        } finally {
            container.stop();
        }
    }

    private static JobHandler probeHandler(AtomicInteger handlerCalls) {
        return new JobHandler() {
            @Override
            public String getJob() {
                return "probe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                handlerCalls.incrementAndGet();
                com.zorrodev.bpm.exchange.ProcessVariable v =
                    new com.zorrodev.bpm.exchange.ProcessVariable();
                v.setName("x");
                v.setValue("1");
                v.setType("STRING");
                return List.of(v);
            }
        };
    }

    private int serverMessageCount(String queue) {
        try {
            ConnectionFactory f = new ConnectionFactory();
            f.setHost(host);
            f.setPort(port);
            f.setUsername(user);
            f.setPassword(password);
            try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
                return ch.queueDeclarePassive(queue).getMessageCount();
            }
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable", e);
        }
    }

    /** Content-check нашего taskId из указанной очереди (чужой мусор ack'ается). */
    private void awaitCompletionFrom(UUID taskId, String queue, Duration timeout) {
        try {
            ConnectionFactory f = new ConnectionFactory();
            f.setHost(host);
            f.setPort(port);
            f.setUsername(user);
            f.setPassword(password);
            try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
                await("completion of task " + taskId + " from " + queue)
                    .atMost(timeout)
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        com.rabbitmq.client.GetResponse resp = ch.basicGet(queue, true);
                        if (resp == null) {
                            return false;
                        }
                        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
                        try {
                            com.zorrodev.bpm.exchange.ServiceTaskCompleteData data =
                                objectMapper.readValue(body,
                                    com.zorrodev.bpm.exchange.ServiceTaskCompleteData.class);
                            return taskId.equals(data.getServiceTaskId())
                                && "SUCCESS".equals(data.getStatus());
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
            }
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new IllegalStateException("timed out waiting for completion of task " + taskId, e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
