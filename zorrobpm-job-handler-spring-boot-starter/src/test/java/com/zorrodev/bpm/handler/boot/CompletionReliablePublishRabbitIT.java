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
import java.util.UUID;
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
        // WO-INT-10: completion идёт через exchange — очередь + identity-биндинг, как прод-топология.
        CompletionExchangeProbe.bindQueue(admin, COMPLETE_QUEUE);
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

    /**
     * Listener на боевом шаблоне: mandatory + publisher-returns на фабрике РОВНО
     * как в {@code HandlerAutoConfiguration}. Решение о доставке принимает сам
     * воркер по {@code CorrelationData.getReturned()} — общий сет возвратов
     * удалён (red-team 1.2: он был источником гонки и несовпадения форматов).
     */
    private JobCompletionListener listenerOn(RabbitTemplate template, JobHandler handler,
            String completeQueue) {
        template.setMandatory(true);
        JobCompletionListener jobListener =
            new JobCompletionListener(handler, template, objectMapper, JOB_QUEUE, completeQueue);
        jobListener.setEnsurePublisherConfirms(true);
        jobListener.setConfirmTimeoutMs(5_000L);
        return jobListener;
    }

    @Test
    void unroutableCompletion_notAcked_recoveredAfterRouteFix_appliedOnce() throws Exception {
        UUID taskId = UUID.randomUUID();
        String correlationId = "c836-c4-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);

        JobHandler handler = probeHandler(handlerCalls);
        RabbitTemplate listenerTemplate = new RabbitTemplate(sendCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            listenerOn(listenerTemplate, handler, NO_SUCH_QUEUE);

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
            assertThat(jobListener.unroutableCountForTest())
                .as("воркер распознал возврат как НЕ доставку (getReturned) — вход не подтверждён "
                    + "(red-team 1.2)")
                .isPositive();

            // «Правка маршрута»: объявляем очередь — следующий redelivery доходит.
            // WO-INT-10: «правка маршрута» = очередь + identity-биндинг к exchange.
            CompletionExchangeProbe.bindQueue(admin, NO_SUCH_QUEUE);
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
        // Локальный на задачу (PoF-правка): ноль ДО старта, чтобы «2 попытки»
        // ниже нельзя было набрать чем-то посторонним.
        AtomicInteger completionSendAttempts = new AtomicInteger(0);
        UUID taskId = UUID.randomUUID();
        String correlationId = "c836-c5-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);

        JobHandler handler = probeHandler(handlerCalls);
        // Живой брокер, живая отправка — но confirm ПЕРВОЙ отправки теряется.
        // Эмуляция ровно того, что делает брокер при потере confirm: сообщение
        // ушло, а future той отправки не завершается. Технически — первая
        // отправка уходит брокеру с ПОДМЕННОЙ CorrelationData (её confirm
        // завершается и пропадает), тогда как listener продолжает ждать future
        // СВОЕЙ (незавершённой) отправки → таймаут → throw → NACK входа.
        // Вторая (redelivery) отправка идёт с честной корреляцией и подтверждается.
        RabbitTemplate flakyTemplate = new RabbitTemplate(sendCf) {
            private final AtomicBoolean firstSend = new AtomicBoolean(true);

            @Override
            public void convertAndSend(String exchange, String routingKey, Object object,
                    org.springframework.amqp.core.MessagePostProcessor messagePostProcessor,
                    org.springframework.amqp.rabbit.connection.CorrelationData correlationData) {
                // POF-правка (P-67): считаем КАЖДУЮ попытку публикации результата.
                // Без этого счётчика тест был зелёным и при снятом confirm-wait
                // (проверено мутацией): «результат доехал, эффект один раз, очередь
                // пуста» выполняется и когда восстановления НЕ было вовсе — первая
                // отправка тоже долетает, просто с decoy-корреляцией. Отличать
                // «восстановлено переотправкой» от «восстановления не требовалось»
                // можно только по СЧЁТЧИКУ попыток.
                completionSendAttempts.incrementAndGet();
                if (firstSend.compareAndSet(true, false) && correlationData != null) {
                    droppedFirstConfirm.set(true);
                    // WO-INT-10: сигнатура стала 5-арной (exchange первый).
                    super.convertAndSend(exchange, routingKey, object, messagePostProcessor,
                        new org.springframework.amqp.rabbit.connection.CorrelationData(
                            correlationData.getId() + "-decoy"));
                    return;
                }
                super.convertAndSend(exchange, routingKey, object, messagePostProcessor, correlationData);
            }
        };
        flakyTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            listenerOn(flakyTemplate, handler, COMPLETE_QUEUE);
        // Таймаут короткий, чтобы потеря confirm не жгла 20 c на прогон.
        jobListener.setConfirmTimeoutMs(700L);

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
            // подтверждается → ACK. Эффект один раз, результат доставлен.
            awaitCompletionFrom(taskId, COMPLETE_QUEUE, Duration.ofSeconds(20));
            assertThat(droppedFirstConfirm.get())
                .as("первый confirm реально потерян (иначе сценарий пуст)")
                .isTrue();
            Thread.sleep(2000);
            // Счётчик проверяется ПОСЛЕ окна восстановления, не сразу после
            // awaitCompletionFrom: тот возвращается уже по ПЕРВОЙ доставке
            // (сообщение опубликовано, decoy-корреляция на маршрутизацию не
            // влияет), а redelivery случается позже. Проверка «сразу» была бы
            // гонкой и видела бы 1 попытку при полностью рабочем коде.
            assertThat(completionSendAttempts.get())
                .as("результат публиковался НЕСКОЛЬКО раз: первая попытка не была "
                    + "принята (потерян confirm → NACK входа → redelivery → новая "
                    + "попытка). Без confirm-wait попыток ровно одна — и все "
                    + "остальные ассерты этого теста всё равно проходят, то есть "
                    + "проверяли бы «восстановления не требовалось» (P-67)")
                .isGreaterThanOrEqualTo(2);
            assertThat(handlerCalls.get())
                .as("бизнес-эффект ровно один раз")
                .isEqualTo(1);
            assertThat(serverMessageCount(JOB_QUEUE))
                .as("вход ACK-нут после успешной переотправки — задание не зависло в очереди")
                .isZero();
        } finally {
            container.stop();
        }
    }

    private final AtomicBoolean droppedFirstConfirm = new AtomicBoolean(false);

    @Test
    void happyPath_confirmedRoutable_inputAcked_completionDelivered() throws Exception {
        // Red-team 1.1/1.2 — тест, которого не было: счастливый путь ОБЯЗАН
        // проверять не только «completion доехал», но и то, что listener вернулся
        // нормой и вход ACK-нут. Именно этот пробел скрывал блокер: старый код
        // бросал IllegalStateException на КАЖДОМ completion, а оба IT были зелёные.
        // Мутация «подтверждение не проверяется / бросается» валит тест здесь.
        UUID taskId = UUID.randomUUID();
        String correlationId = "c836-happy-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);

        // Шаблон отправки — с ТЕМ ЖЕ конвертером, что у контейнера
        // (SimpleMessageConverter не умеет POJO: без этого падает
        // «only supports String, byte[] and Serializable payloads»).
        RabbitTemplate listenerTemplate = new RabbitTemplate(sendCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            listenerOn(listenerTemplate, probeHandler(handlerCalls), COMPLETE_QUEUE);
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

            awaitCompletionFrom(taskId, COMPLETE_QUEUE, Duration.ofSeconds(20));
            assertThat(handlerCalls.get()).isEqualTo(1);
            assertThat(serverMessageCount(JOB_QUEUE))
                .as("вход ACK-нут: onMessage вернулся нормой (confirm ack, не unroutable)")
                .isZero();
            assertThat(jobListener.unroutableCountForTest())
                .as("маршрутизируемый результат возвратов не имеет — вход ACK-нут штатно")
                .isZero();
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
