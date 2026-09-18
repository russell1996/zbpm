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

/**
 * WO-TEST-10 scenario 3 (WB-002): обрыв сети к RabbitMQ посреди processing — реальный инжект.
 *
 * <p>Инжект: toxiproxy {@code timeout:0} в обе стороны между консьюмером и брокером
 * (адресация через {@code CHAOS_RABBIT_HOST/PORT} — в chaos-топологии это toxiproxy-listen,
 * upstream — живой rabbitmq:3.13; вне chaos-прогона — прямое соединение и тест
 * self-skip'ается). Окно partition — до 10 секунд активного processing.
 *
 * <p>Проверяемое поведение: (1) Spring AMQP autorecovery переподключается БЕЗ рестарта
 * контейнера — тот же {@code SimpleMessageListenerContainer} продолжает доставлять
 * после снятия toxic'а; (2) сообщение, бывшее в processing в момент обрыва, НЕ потеряно:
 * unacked уходит в requeue и доводится до completion; (3) ровно-один эффект:
 * {@code JobCompletionListener} result-cache (ключ — correlationId) гасит дубль при
 * redelivery, handler вызывается один раз.
 *
 * <p>Отличие от {@code CompletionTransportRabbitIT} (WO-REL-36): там рвётся транспорт
 * ОТПРАВКИ (destroy send-фабрики, consumer-канал жив — дискриминатор swallow-vs-throw);
 * здесь рвётся ВЕСЬ канал консьюмера (downstream+upstream — как при сетевом partition),
 * и проверяется именно autorecovery + redelivery-путь, а не разделение ошибок отправки.
 *
 * <p>Self-skip: без {@code CHAOS_TOXI_HOST} — assumptions-skip (P-34).
 *
 * <p>POF (V3/G-N): мутация «completion без result-cache» (всегда вызывать handler)
 * дала бы handlerCalls==2 после redelivery — здесь POF идёт инверсией: тот же каркас
 * с partition, НЕ снятым ( toxic навсегда), обязан RED — completion не приходит.
 * См. {@code rabbitPartitionNeverHeals_red}.
 */
@Tag("chaos")
class RabbitPartitionChaosIT {

    private static final String JOB_QUEUE = "chaos.it.jobs.netprobe";
    private static final String COMPLETE_QUEUE = "chaos.it.complete.netprobe";

    private String host;
    private int port;
    private String user;
    private String password;
    private String toxiBaseOrNull;

    private CachingConnectionFactory cf;
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
    void setUp() throws Exception {
        host = cfg("CHAOS_RABBIT_HOST", cfg("RABBITMQ_HOST", "localhost"));
        port = Integer.parseInt(cfg("CHAOS_RABBIT_PORT", cfg("RABBITMQ_PORT", "5672")));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");
        String toxiHost = System.getenv("CHAOS_TOXI_HOST");
        if (toxiHost == null) {
            toxiHost = System.getProperty("CHAOS_TOXI_HOST");
        }
        toxiBaseOrNull = toxiHost == null ? null
            : "http://" + toxiHost + ":" + cfg("CHAOS_TOXI_PORT", "8474");
        // Sweep остатков мёртвого прогона: toxiproxy пережил бывшую JVM — чужие
        // toxic'и обязаны исчезнуть ДО baseline, иначе ложный RED.
        if (toxiBaseOrNull != null) {
            for (String t : List.of(TOXIC_A, TOXIC_A + "-down", TOXIC_PERM, TOXIC_PERM + "-down",
                    "cut", "cut-down", "perm", "perm-down")) {
                try {
                    toxi("DELETE", "/proxies/chaos-rabbit/toxics/" + t, null);
                } catch (Exception ignored) {
                }
            }
        }

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        // Autorecovery — прод-дефолт CachingConnectionFactory; partition-тест проверяет
        // именно его (без recovery контейнер умер бы вместе с каналом).
        admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new org.springframework.amqp.core.Queue(JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(COMPLETE_QUEUE, true, false, false));
        admin.purgeQueue(JOB_QUEUE, false);
        admin.purgeQueue(COMPLETE_QUEUE, false);
        assertThat(serverMessageCount(JOB_QUEUE)).as("вход пуст на старте").isZero();

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(cf);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        containerFactory.setMessageConverter(converter);

        senderTemplate = new RabbitTemplate(cf);
        senderTemplate.setMessageConverter(converter);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        // Оборонительный heal: даже упавший тест не оставляет partition следующим.
        healQuietly(TOXIC_A);
        healQuietly(TOXIC_A + "-down");
        healQuietly(TOXIC_PERM);
        healQuietly(TOXIC_PERM + "-down");
        try {
            admin.purgeQueue(JOB_QUEUE, false);
            admin.purgeQueue(COMPLETE_QUEUE, false);
        } catch (Exception ignored) {
        }
        try {
            cf.destroy();
        } catch (Exception ignored) {
        }
    }

    /** Heal, терпящий отсутствие toxic'а ГРОМКО (warn), а не маскировкой исключения. */
    private void healQuietly(String toxic) {
        if (toxiBaseOrNull == null) {
            return;
        }
        try {
            toxi("DELETE", "/proxies/chaos-rabbit/toxics/" + toxic, null);
            System.out.println("[chaos-rabbit] healed toxic " + toxic);
        } catch (Exception e) {
            System.out.println("[chaos-rabbit] WARN: heal of " + toxic + " failed: " + e + " — continuing");
        }
    }

    /** Verify: после heal toxic'ов быть не должно — иначе hard fail, не тишина. */
    private void verifyNoToxics() throws Exception {
        // Лёгкий GET через toxi(): 200 с телом-списком; пусто == "[]" или "{}".
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(toxiBaseOrNull + "/proxies/chaos-rabbit/toxics"))
            .timeout(java.time.Duration.ofSeconds(15)).GET().build();
        java.net.http.HttpResponse<String> resp =
            http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body().replaceAll("\\s", ""))
            .as("после heal toxic'ов остаться не должно: " + resp.body())
            .isIn("[]", "{}");
    }

    private void toxi(String method, String path, String body) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(toxiBaseOrNull + path))
            .timeout(java.time.Duration.ofSeconds(15));
        switch (method) {
            case "POST" -> b.header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        }
        java.net.http.HttpResponse<String> resp =
            http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + path + " -> HTTP " + resp.statusCode());
        }
    }

    private static final String TOXIC_A = "cut-rb-a";
    private static final String TOXIC_PERM = "perm-rb-a";

    private int serverMessageCount(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setConnectionTimeout(5000);
        f.setHandshakeTimeout(5000);
        f.setShutdownTimeout(5000);
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        }
    }

    private static JobDetailModel jobDetail(UUID serviceTaskId) {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob("netprobe");
        detail.setVariables(Map.of());
        return detail;
    }

    @Test
    void rabbitPartitionMidProcessing_recoveryNoRestart_effectOnce() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(toxiBaseOrNull != null,
            "chaos topology only: CHAOS_TOXI_HOST not set (ci/run-chaos-tests.sh)");

        UUID taskId = UUID.randomUUID();
        String correlationId = "chaos-net-" + UUID.randomUUID();
        AtomicInteger handlerCalls = new AtomicInteger(0);
        // Latch-координация (прецедент CompletionTransportRabbitIT): handler входит и
        // ВИСИТ, пока тест не разрешит — инжект происходит строго ПОСЛЕ входа в handler
        // (сообщение unacked) и строго ДО publish completion. Без координации тест
        // гонялся бы с реальной гонкой consume-vs-cut (вакуозный POF).
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);
        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "netprobe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                handlerCalls.incrementAndGet();
                entered.countDown();
                try {
                    if (!releaseWork.await(30, TimeUnit.SECONDS)) {
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
        RabbitTemplate listenerTemplate = new RabbitTemplate(cf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener = new JobCompletionListener(
            handler, listenerTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);

        SimpleMessageListenerContainer container = containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
                m.getMessageProperties().setCorrelationId(correlationId);
                return m;
            });

            // Ждём входа в handler (сообщение unacked), затем ИНЖЕКТ — partition бьёт
            // строго посреди обработки, а не «где-то рядом».
            assertThat(entered.await(30, TimeUnit.SECONDS)).as("handler вошёл").isTrue();
            toxi("POST", "/proxies/chaos-rabbit/toxics",
                "{\"name\":\"" + TOXIC_A + "\",\"type\":\"timeout\",\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
            toxi("POST", "/proxies/chaos-rabbit/toxics",
                "{\"name\":\"" + TOXIC_A + "-down\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
            try {
                // Окно partition: канал мёртв, handler висит. Держим достаточно, чтобы
                // обрыв был реальным (heartbeat-дефолты — обрыв виден не мгновенно).
                Thread.sleep(12000);
            } finally {
                healQuietly(TOXIC_A);
                healQuietly(TOXIC_A + "-down");
                verifyNoToxics();
            }

            // Отпускаем handler ПОСЛЕ heal: redelivery идёт из result-cache... нет —
            // первая доставка: handler возвращается, completion отправляется по живой
            // связи. Ключевое: handler вызван РОВНО один раз за весь сценарий.
            releaseWork.countDown();

            // Recovery БЕЗ рестарта контейнера: autorecovery переподключает тот же
            // контейнер, requeue доводится до completion, дубль гасится result-cache.
            // Наблюдатель потребляет completion СРАЗУ (basicGet+ack).
            awaitCompletionForTask(taskId, Duration.ofSeconds(45));
            assertThat(handlerCalls.get())
                .as("ровно-один эффект: redelivery идёт из result-cache, handler не повторяется")
                .isEqualTo(1);
            assertThat(container.isRunning()).as("контейнер пережил partition без рестарта").isTrue();
            // Стоп консьюмера ПЕРЕД drain-assert'ом: при рваном ack redelivery hot-loop
            // (ack не дошёл — ожидаемо) иначе гоняет completion бесконечно, и drain
            // никогда не видит стабильный 0. После stop redelivery прекращается,
            // наблюдатель доедает COMPLETE до 0 — детерминированно (урок run6).
            // NB: второго serverMessageCount-assert'а до stop НЕТ сознательно: вход мог
            // redeliver'иться (ack не дошёл по рваному каналу) — result-cache при этом
            // переотправляет ТОТ ЖЕ completion (at-least-once по дизайну WO-REL-36,
            // движок дедуплицирует по correlationId/outboxId). Доказанное свойство —
            // handlerCalls==1 + drained-после-stop, а не «в очереди навсегда пусто».
            container.stop();
            // АКТИВНЫЙ drain вместо пассивного ожидания: awaitCompletionForTask выше уже
            // вышел при первом completion и больше не потребляет; redelivery-хвост,
            // накопленный за partition (result-cache переотправляет ТОТ ЖЕ completion —
            // at-least-once по дизайну WO-REL-36), никто не доедает. Тест сам забирает
            // остаток basicGet+ack до стабильного 0 — доказанное свойство то же самое
            // (всё потребляемо, очередь сходится к реальному нулю), без зависимости от
            // вышедшего наблюдателя (урок run8: пассивный await висел 15с на хвосте).
            drainCompletely(COMPLETE_QUEUE, Duration.ofSeconds(15));
            assertThat(serverMessageCount(COMPLETE_QUEUE))
                .as("completion потреблён наблюдателем")
                .isZero();
        } finally {
            try {
                container.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void rabbitPartitionNeverHeals_red() throws Exception {
        // POF-инверсия: partition НЕ снимается — completion прийти НЕ может, await
        // обязан RED (таймаут). Доказывает, что позитивный тест проверяет именно
        // восстановление связи, а не «сообщение всегда доходит».
        org.junit.jupiter.api.Assumptions.assumeTrue(toxiBaseOrNull() != null,
            "chaos topology only: CHAOS_TOXI_HOST not set (ci/run-chaos-tests.sh)");
        UUID taskId = UUID.randomUUID();

        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "netprobe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                com.zorrodev.bpm.exchange.ProcessVariable v =
                    new com.zorrodev.bpm.exchange.ProcessVariable();
                v.setName("x");
                v.setValue("1");
                v.setType("STRING");
                return List.of(v);
            }
        };
        RabbitTemplate listenerTemplate = new RabbitTemplate(cf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener = new JobCompletionListener(
            handler, listenerTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);
        SimpleMessageListenerContainer container = containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
                m.getMessageProperties().setCorrelationId("chaos-never-" + UUID.randomUUID());
                return m;
            });
            Thread.sleep(1500);
            toxi("POST", "/proxies/chaos-rabbit/toxics",
                "{\"name\":\"" + TOXIC_PERM + "\",\"type\":\"timeout\",\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
            toxi("POST", "/proxies/chaos-rabbit/toxics",
                "{\"name\":\"" + TOXIC_PERM + "-down\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
            try {
                boolean arrived = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (System.nanoTime() < deadline && !arrived) {
                    try {
                        awaitCompletionForTask(taskId, Duration.ofSeconds(2));
                        arrived = true;
                    } catch (IllegalStateException e) {
                        // ожидаемо: связи нет
                    }
                }
                assertThat(arrived).as("без снятия partition completion невозможен — обязано RED").isFalse();
            } finally {
                healQuietly(TOXIC_PERM);
                healQuietly(TOXIC_PERM + "-down");
            }
        } finally {
            container.stop();
        }
    }

    private String toxiBaseOrNull() {
        return toxiBaseOrNull;
    }

    private void awaitCompletionForTask(UUID taskId, Duration timeout) throws Exception {
        // Наблюдатель идёт НАПРЯМУЮ в брокер (raw ConnectionFactory, не через cf):
        // соединения пула приложения могут висеть в partition, наблюдатель обязан
        // оставаться независимым (урок run4: TimeoutException наблюдателя вместо RED).
        long deadline = System.nanoTime() + timeout.toNanos();
        ConnectionFactory f = new ConnectionFactory();
        // Короткие таймауты рукопожатия: при мёртвой связи basicGet падает быстро,
        // а не висит весь timeout одной попытки.
        f.setConnectionTimeout(5000);
        f.setHandshakeTimeout(5000);
        f.setShutdownTimeout(5000);
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        // Одна попытка — одно соединение: try-with-resources закрывает его сразу,
        // полуоткрытые сокеты не копятся между итерациями.
        while (System.nanoTime() < deadline) {
            try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
                ch.basicQos(1);
                com.rabbitmq.client.GetResponse resp = ch.basicGet(COMPLETE_QUEUE, true);
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
                    }
                } else {
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                // Связи нет (partition) или брокер ещё не вернулся — ждём дальше в
                // пределах дедлайна, а не падаем первой же ошибкой соединения.
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("timed out waiting for completion of task " + taskId);
    }

    /**
     * Активно забирает очередь до стабильного нуля (basicGet+ack в цикле).
     * Ноль считается достигнутым после 5 подряд пустых basicGet (~1с тишины) —
     * одиночный пустой ответ посреди redelivery-хвоста нулём не считается.
     */
    private void drainCompletely(String queue, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ConnectionFactory f = new ConnectionFactory();
        f.setConnectionTimeout(5000);
        f.setHandshakeTimeout(5000);
        f.setShutdownTimeout(5000);
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        int quiet = 0;
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            while (System.nanoTime() < deadline) {
                com.rabbitmq.client.GetResponse resp = ch.basicGet(queue, true);
                if (resp == null) {
                    if (++quiet >= 5) {
                        return;
                    }
                    Thread.sleep(200);
                } else {
                    quiet = 0;
                }
            }
        }
        throw new IllegalStateException("timed out draining queue " + queue);
    }
}
