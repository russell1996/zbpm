package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-64, DoD на живом RabbitMQ: отравленный результат не блокирует соседние;
 * после починки маршрута уходит повтором из poison-очереди, эффект ровно один раз.
 *
 * <p>«Отравление» — детерминированное и без разрушения соединений: completion
 * публикуется mandatory-шаблоном с CORRELATED-confirms (как прод-шаблон стартера,
 * см. {@code HandlerAutoConfiguration}) на ключ, под которым очереди НЕТ —
 * брокер возвращает basic.return, {@code ConfirmedCompletionSender} бросает, и
 * результат идёт по шкале попыток в poison-парковку. «Починка маршрута» —
 * declare очереди под тем же ключом (default exchange): повтор из poison
 * доставляется, тело verbatim, completionId тот же.
 */
@Tag("rabbit")
class CompletionPoisonRabbitIT {

    private static final String JOBS_POISON = "rel64.it.jobs.poison";
    private static final String JOBS_OK = "rel64.it.jobs.ok";
    private static final String COMPLETE = "rel64.it.complete";
    /** Ключ без очереди — «битый маршрут»; declare позже = «маршрут починен». */
    private static final String BLACKHOLE = "rel64.it.complete.blackhole";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory consumeCf;
    private CachingConnectionFactory sendCf;
    private RabbitAdmin admin;
    private SimpleRabbitListenerContainerFactory containerFactory;
    private RabbitTemplate senderTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() throws Exception {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        consumeCf = cachingFactory();
        sendCf = cachingFactory();
        sendCf.setPublisherConfirmType(
            CachingConnectionFactory.ConfirmType.CORRELATED);

        admin = new RabbitAdmin(sendCf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new Queue(JOBS_POISON, true, false, false));
        admin.declareQueue(new Queue(JOBS_OK, true, false, false));
        admin.declareQueue(new Queue(COMPLETE, true, false, false));
        // WO-REL-64: топология парковки — тем же прод-путём, что стартер.
        HandlerAutoConfiguration.declarePoisonTopology(admin);
        admin.purgeQueue(JOBS_POISON, false);
        admin.purgeQueue(JOBS_OK, false);
        admin.purgeQueue(COMPLETE, false);
        admin.purgeQueue(CompletionPoisonRetryListener.POISON_QUEUE, false);
        admin.purgeQueue(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE, false);
        try {
            assertThat(serverMessageCount(JOBS_POISON)).as("вход poison пуст").isZero();
            assertThat(serverMessageCount(JOBS_OK)).as("вход ok пуст").isZero();
            assertThat(serverMessageCount(COMPLETE)).as("completion пуст").isZero();
            assertThat(serverMessageCount(
                CompletionPoisonRetryListener.POISON_QUEUE)).as("poison пуст").isZero();
        } catch (AssertionError e) {
            throw new IllegalStateException("stale messages on broker, purge failed", e);
        }

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(consumeCf);
        containerFactory.setMessageConverter(
            new Jackson2JsonMessageConverter(new ObjectMapper()));

        senderTemplate = new RabbitTemplate(sendCf);
        senderTemplate.setMessageConverter(
            new Jackson2JsonMessageConverter(new ObjectMapper()));
        senderTemplate.setMandatory(true);
    }

    @AfterEach
    void tearDown() {
        for (String q : List.of(JOBS_POISON, JOBS_OK, COMPLETE,
                CompletionPoisonRetryListener.POISON_QUEUE,
                CompletionPoisonRetryListener.RETRY_DELAY_QUEUE, BLACKHOLE)) {
            try {
                admin.purgeQueue(q, false);
            } catch (Exception ignored) {
            }
        }
        try {
            admin.deleteQueue(BLACKHOLE);
        } catch (Exception ignored) {
        }
        destroyQuietly(sendCf);
        destroyQuietly(consumeCf);
    }

    @Test
    void poisonedResult_parkedAfterCeiling_neighborFlows_retryDeliversOnce()
            throws Exception {
        AtomicInteger poisonHandlerCalls = new AtomicInteger(0);
        JobCompletionListener poisonListener = new JobCompletionListener(
            countingHandler("poison-probe", poisonHandlerCalls),
            workerTemplate(), objectMapper, JOBS_POISON, BLACKHOLE);
        // Потолок 3: шкала 1с+2с — парковка примерно через 3с после первой неудачи.
        poisonListener.setMaxCompletionAttempts(3);

        AtomicInteger okHandlerCalls = new AtomicInteger(0);
        JobCompletionListener okListener = new JobCompletionListener(
            countingHandler("ok-probe", okHandlerCalls),
            workerTemplate(), objectMapper, JOBS_OK, COMPLETE);

        SimpleMessageListenerContainer containerA = containerFactory.createListenerContainer();
        containerA.setQueueNames(JOBS_POISON);
        containerA.setMessageListener(poisonListener);
        containerA.setConcurrentConsumers(1);
        SimpleMessageListenerContainer containerB = containerFactory.createListenerContainer();
        containerB.setQueueNames(JOBS_OK);
        containerB.setMessageListener(okListener);
        containerB.setConcurrentConsumers(1);
        containerA.start();
        containerB.start();
        try {
            // 1. Отравленное задание: completion немаршрутизируем (очереди
            // BLACKHOLE нет) — return → throw → backoff → парковка.
            UUID poisonTask = UUID.randomUUID();
            String poisonCorr = "rel64-poison-" + UUID.randomUUID();
            senderTemplate.convertAndSend(JOBS_POISON, jobDetail(poisonTask, "poison-probe"),
                m -> {
                    m.getMessageProperties().setCorrelationId(poisonCorr);
                    return m;
                });

            // 2. Пока отравленное крутится в backoff на своём потоке —
            // соседнее задание на соседней очереди обязано течь.
            // WO-QW-7: намеренно Thread.sleep — нужно попасть ВНУТРЬ чужого
            // backoff-окна (наступление = первая неудача уже случилась, тихий
            // сон слушателя A идёт), ждать здесь нечего.
            Thread.sleep(1500);
            UUID okTask = UUID.randomUUID();
            senderTemplate.convertAndSend(JOBS_OK, jobDetail(okTask, "ok-probe"),
                m -> {
                    m.getMessageProperties().setCorrelationId("rel64-ok-" + UUID.randomUUID());
                    return m;
                });
            awaitCompletionWithTaskId(COMPLETE, okTask, Duration.ofSeconds(20));
            assertThat(okHandlerCalls.get()).as("сосед отработал один раз").isEqualTo(1);

            // 3. Потолок достигнут: копия в poison — тот же completionId,
            // попытки = 3, вход подтверждён (очередь течёт, см. п.2).
            ParkedCopy parked = awaitParkedCopy(Duration.ofSeconds(30));
            assertThat(parked.attempts())
                .as("парковка после ровно maxCompletionAttempts неудач").isEqualTo(3);
            assertThat(parked.body().getServiceTaskId()).isEqualTo(poisonTask);
            assertThat(parked.body().getStatus()).isEqualTo("SUCCESS");
            assertThat(poisonListener.poisonedCountForTest()).isEqualTo(1L);
            assertThat(poisonHandlerCalls.get())
                .as("бизнес-эффект отравленного — один раз (повторы из кэша)")
                .isEqualTo(1);

            // 4. Маршрут починен: очередь под ключом появилась. Повтор из
            // poison отдельным слушателем — доставка ровно один раз.
            admin.declareQueue(new Queue(BLACKHOLE, true, false, false));
            CompletionPoisonRetryListener retryListener =
                new CompletionPoisonRetryListener(workerTemplate(), objectMapper, BLACKHOLE);
            SimpleMessageListenerContainer containerC =
                containerFactory.createListenerContainer();
            containerC.setQueueNames(CompletionPoisonRetryListener.POISON_QUEUE);
            containerC.setMessageListener(retryListener);
            containerC.setConcurrentConsumers(1);
            containerC.start();
            try {
                awaitCompletionWithId(BLACKHOLE, parked.body().getCompletionId(),
                    Duration.ofSeconds(20));
                assertThat(retryListener.retryDeliveredCountForTest()).isEqualTo(1L);
                assertThat(serverMessageCount(
                    CompletionPoisonRetryListener.POISON_QUEUE))
                    .as("poison-копия потреблена (ACK после доставки)")
                    .isZero();
                // WO-QW-7: намеренно Thread.sleep — условие инвертировано
                // (вторая доставка НЕ должна произойти; тишина 3с = доказательство).
                Thread.sleep(3000);
                assertThat(countCompletionsWithId(BLACKHOLE, parked.body().getCompletionId()))
                    .as("повтор доставил ровно одну копию (дедуп по completionId)")
                    .isEqualTo(0);
                assertThat(poisonHandlerCalls.get())
                    .as("повтор handler не вызывает никогда")
                    .isEqualTo(1);
            } finally {
                containerC.stop();
            }
        } finally {
            containerA.stop();
            containerB.stop();
        }
    }

    /**
     * WO-REL-64 (red-team M-3): центральный механизм автоповтора — копия в
     * delay-очереди с per-message TTL истекает и возвращается в poison через
     * DLX. Недоказанным оставалось ровно это: если бы {@code expiration}
     * переживал dead-lettering, копия истекала бы уже в poison (у него DLX
     * нет — брокер такие молча дропает, «не дропается никогда» нарушено).
     */
    @Test
    void delayCopy_expiresBackToPoison_intactAndNotExpiringThere() throws Exception {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(UUID.randomUUID());
        data.setStatus("SUCCESS");
        data.setCompletionId("rel64-delay-" + UUID.randomUUID());
        int attempts = 11;

        RabbitTemplate delayTemplate = workerTemplate();
        delayTemplate.convertAndSend(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE, data,
            m -> {
                m.getMessageProperties().setHeader(
                    CompletionPoisonRetryListener.HDR_ATTEMPTS, attempts);
                m.getMessageProperties().setExpiration("1000");
                return m;
            });

        // Копия вернулась в poison через DLX — тело и счётчик целы.
        ParkedCopy back = awaitParkedCopyWithId(data.getCompletionId(), Duration.ofSeconds(20));
        assertThat(back.attempts()).isEqualTo(attempts);
        assertThat(back.body().getServiceTaskId()).isEqualTo(data.getServiceTaskId());

        // WO-QW-7: намеренно Thread.sleep — условие инвертировано (копия НЕ
        // должна истечь повторно в poison; тишина 3с = доказательство, что
        // expiration не пережил dead-lettering и брокер её не дропнул).
        Thread.sleep(3000);
        ParkedCopy stillThere = pollParkedCopyWithId(data.getCompletionId());
        assertThat(stillThere)
            .as("возвращённая копия живёт в poison (TTL не пережил DLX, дропа нет)")
            .isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private CachingConnectionFactory cachingFactory() {
        CachingConnectionFactory cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        return cf;
    }

    /** Прод-подобный шаблон воркера: CORRELATED-confirms + mandatory. */
    private RabbitTemplate workerTemplate() {
        RabbitTemplate t = new RabbitTemplate(sendCf);
        t.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        t.setMandatory(true);
        return t;
    }

    private static void destroyQuietly(CachingConnectionFactory cf) {
        try {
            cf.destroy();
        } catch (Exception ignored) {
        }
    }

    private static JobHandler countingHandler(String job, AtomicInteger calls) {
        return new JobHandler() {
            @Override
            public String getJob() {
                return job;
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(
                    JobDetailModel model) {
                calls.incrementAndGet();
                com.zorrodev.bpm.exchange.ProcessVariable v =
                    new com.zorrodev.bpm.exchange.ProcessVariable();
                v.setName("x");
                v.setValue("1");
                v.setType("STRING");
                return List.of(v);
            }
        };
    }

    private static JobDetailModel jobDetail(UUID serviceTaskId, String job) {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob(job);
        detail.setVariables(Map.of());
        return detail;
    }

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

    private void awaitCompletionWithTaskId(String queue, UUID taskId, Duration timeout) {
        await("completion of task " + taskId)
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                ServiceTaskCompleteData data = pollCompletion(queue);
                return data != null && taskId.equals(data.getServiceTaskId())
                    && "SUCCESS".equals(data.getStatus());
            });
    }

    private void awaitCompletionWithId(String queue, String completionId, Duration timeout) {
        await("completion " + completionId)
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                ServiceTaskCompleteData data = pollCompletion(queue);
                return data != null && completionId.equals(data.getCompletionId());
            });
    }

    /** Выгребает очередь до первого completion; чужой мусор отбрасывает. */
    private ServiceTaskCompleteData pollCompletion(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            for (int i = 0; i < 50; i++) {
                com.rabbitmq.client.GetResponse resp = ch.basicGet(queue, true);
                if (resp == null) {
                    return null;
                }
                try {
                    return objectMapper.readValue(resp.getBody(), ServiceTaskCompleteData.class);
                } catch (Exception ignored) {
                    // Чужой мусор — отброшен ack'ом, смотрим дальше.
                }
            }
            return null;
        }
    }

    private record ParkedCopy(ServiceTaskCompleteData body, int attempts) {
    }

    private ParkedCopy awaitParkedCopy(Duration timeout) {
        return await("parked poison copy")
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(300))
            .until(() -> pollParkedCopy(), copy -> copy != null);
    }

    /**
     * Ждёт в poison копию с КОНКРЕТНЫМ completionId (content-check, не счётчик:
     * чужой stale от соседнего прогона давал бы ложный GREEN). Чужое — NACK +
     * requeue (остаётся в очереди), совпавшее — тоже requeue (заберёт
     * retry-слушатель шага 4 / проверка M-3).
     */
    private ParkedCopy awaitParkedCopyWithId(String completionId, Duration timeout) {
        return await("parked poison copy " + completionId)
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(300))
            .until(() -> pollParkedCopyWithId(completionId), copy -> copy != null);
    }

    private ParkedCopy pollParkedCopyWithId(String completionId) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            for (int i = 0; i < 20; i++) {
                com.rabbitmq.client.GetResponse resp = ch.basicGet(
                    CompletionPoisonRetryListener.POISON_QUEUE, false);
                if (resp == null) {
                    return null;
                }
                ch.basicNack(resp.getEnvelope().getDeliveryTag(), false, true);
                ServiceTaskCompleteData body;
                try {
                    body = objectMapper.readValue(resp.getBody(), ServiceTaskCompleteData.class);
                } catch (Exception ignored) {
                    continue;
                }
                if (completionId.equals(body.getCompletionId())) {
                    return toParkedCopy(resp, body);
                }
            }
            return null;
        }
    }

    private ParkedCopy toParkedCopy(com.rabbitmq.client.GetResponse resp,
            ServiceTaskCompleteData body) {
        java.util.Map<String, Object> headers = resp.getProps().getHeaders();
        Object hdr = headers == null ? null
            : headers.get(CompletionPoisonRetryListener.HDR_ATTEMPTS);
        int attempts = hdr instanceof Number n ? n.intValue() : -1;
        if (body.getCompletionId() == null || attempts < 0) {
            return null;
        }
        return new ParkedCopy(body, attempts);
    }

    private ParkedCopy pollParkedCopy() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            // Один взгляд за опрос, БЕЗ потребления: копия обязана остаться в
            // очереди — её заберёт retry-слушатель шага 4. autoAck=false +
            // requeue в обоих исходах (совпала/нет).
            com.rabbitmq.client.GetResponse resp = ch.basicGet(
                CompletionPoisonRetryListener.POISON_QUEUE, false);
            if (resp == null) {
                return null;
            }
            ch.basicNack(resp.getEnvelope().getDeliveryTag(), false, true);
            ServiceTaskCompleteData body;
            try {
                body = objectMapper.readValue(resp.getBody(), ServiceTaskCompleteData.class);
            } catch (Exception e) {
                return null;
            }
            return toParkedCopy(resp, body);
        }
    }

    /**
     * Сколько completion'ов с этим id СЕЙЧАС в очереди (basicGet потребляет —
     * очередь приватна тесту, счёт идёт выгребанием; 0 = ровно одна доставка
     * была потреблена ожиданием выше и дублей нет).
     */
    private int countCompletionsWithId(String queue, String completionId) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        int found = 0;
        int seen = 0;
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            while (seen < 50) {
                com.rabbitmq.client.GetResponse resp = ch.basicGet(queue, true);
                if (resp == null) {
                    break;
                }
                seen++;
                try {
                    ServiceTaskCompleteData data = objectMapper.readValue(resp.getBody(),
                        ServiceTaskCompleteData.class);
                    if (completionId.equals(data.getCompletionId())) {
                        found++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }
}
