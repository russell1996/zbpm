package com.zorrodev.bpm.rest.resource;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-8 (NEW2-15): sustained FEEL-перегрузка на AMQP-пути завершений —
 * не потеря.
 *
 * <p>Цепочка по коду: временная перегрузка → {@code ScriptOverloadException}
 * наружу без обёртки → контейнерный retry переигрывает то же сообщение
 * (юнит {@code ServiceTaskCompleteListenerTraceTest.qw5_...}, QW-5). Устойчивая
 * перегрузка (retry-лимит контейнера исчерпан — воркер сдаётся) → reject с
 * {@code requeue=false} → брокер паркует тело в DLQ, а не роняет и не крутит
 * вечно. Возврат — вручную (Management UI → republish в основную очередь;
 * идемпотентность держат дедуп C8-36 + replay-guard A-NEW4-15 — см.
 * {@code docs/guides/integration-quickstart.md}, раздел DLQ).
 *
 * <p>Форма (G-N: очередь/DLX/DLQ/биндинг объявляет НАСТОЯЩИЙ прод-бин
 * {@code RabbitConfiguration}, а не копия его аргументов, — убери DLX-аргумент
 * в прод-коде и parked-тест уходит в RED таймаутом ожидания DLQ).
 * P-10: publish и consume на ОДНОМ канале (basicPublish асинхронен — get на
 * другом соединении гоняется с ним и видит пустую очередь).
 *
 * <p>Изоляция (пост-мерж дефект, CI pipeline 176843 job 560518): очередь и DLQ
 * УНИКАЛЬНЫ на запуск (суффикс), а не общие {@code COMPLETE_QUEUE}/{@code
 * COMPLETE_DLQ}. Причина: в той же failsafe-JVM раньше идут Spring-IT на
 * {@code TestMain} (напр. {@code RabbitOutboxConfirmIT},
 * {@code RabbitMqMgmtPathPrefixRabbitIT}), чей закэшированный контекст
 * сканирует {@code com.zorrodev.bpm.rabbitmq} и держит ЖИВОЙ
 * {@code ServiceTaskListener @RabbitListener} на общей очереди — он забирает
 * наше сообщение раньше нашего {@code basicGet} (в логе:
 * {@code Service task to complete message received} + {@code
 * ConditionalRejectingErrorHandler: Execution of Rabbit message listener
 * failed} прямо перед RED). {@code @DirtiesContext} здесь бесполезен: у этого
 * класса контекста нет, а чужой закэшированный им не трогается; останавливать
 * чужие контейнеры из plain-JUnit теста недоступно. Аргументы очереди (DLX)
 * при этом КОПИРУЮТСЯ из настоящего прод-бина (не инлайн) — G-N сохранён:
 * мутант без DLX по-прежнему даёт RED таймаутом. Общий durable DLX-exchange
 * переиспользуется (idempotent declare); routing-key и DLQ — уникальны, чужих
 * копий в общей DLQ быть не может. Бонус: teardown больше НЕ удаляет общие
 * прод-очереди, о которые спотыкаются чужие живые контейнеры.
 *
 * <p>Запуск: {@code RABBITMQ_PORT=5674 ...} + прогон failsafe {@code -Dgroups=rabbit}
 * (CI — {@code ci/run-rabbit-tests.sh}; модуль zorrobpm-rest входит в его -pl).
 * Урок (hard-constrain): новый Rabbit-IT проверяется ПОЛНЫМ прогоном
 * {@code run-rabbit-tests.sh}, не одиночным классом.
 */
@Tag("rabbit")
class CompleteOverloadDlqRabbitIT {

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory cf;
    private RabbitAdmin admin;

    /** Уникальные имена топологии этого запуска (изоляция от чужих контейнеров). */
    private String queue;
    private String dlq;
    private String routingKey;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setup() {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();

        // G-N: топология — из НАСТОЯЩЕГО прод-бина (тот же вызов, что Spring
        // делает при старте движка), не копия аргументов. Имена — уникальны на
        // запуск (см. шапку: изоляция от живых @RabbitListener чужих
        // контекстов); аргументы очереди (в т.ч. DLX) — КОПИЯ карты настоящего
        // прод-бина с подменой только routing-key на уникальный: прод-мутант
        // без DLX по-прежнему даёт RED таймаутом. Общий durable DLX-exchange
        // переиспользуется (declare идемпотентен).
        RabbitConfiguration prod = new RabbitConfiguration();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        queue = RabbitConfiguration.COMPLETE_QUEUE + ".probe-" + runId;
        routingKey = "audit8-probe-" + runId;
        dlq = queue + ".dlq";
        java.util.Map<String, Object> queueArgs =
            new java.util.LinkedHashMap<>(prod.completeServiceTaskQueue().getArguments());
        queueArgs.put("x-dead-letter-routing-key", routingKey);
        admin.declareExchange(prod.completeServiceTaskDlx());
        admin.declareQueue(new org.springframework.amqp.core.Queue(queue, true, false, false, queueArgs));
        admin.declareQueue(new org.springframework.amqp.core.Queue(dlq, true, false, false));
        admin.declareBinding(new org.springframework.amqp.core.Binding(dlq,
            org.springframework.amqp.core.Binding.DestinationType.QUEUE,
            RabbitConfiguration.COMPLETE_DLX, routingKey, null));
        admin.purgeQueue(queue, false);
        admin.purgeQueue(dlq, false);
    }

    @AfterEach
    void teardown() {
        try {
            // Только СВОИ уникальные очереди — общие прод-очереди не трогаем
            // (их слушают живые контейнеры чужих контекстов).
            admin.deleteQueue(queue);
            admin.deleteQueue(dlq);
        } catch (Exception ignored) {
        }
        if (cf != null) {
            cf.destroy();
        }
    }

    @Test
    void sustainedOverload_rejectedParkedInCompleteDlq_notLost() throws Exception {
        byte[] completion = ("{\"serviceTaskId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")
            .getBytes(StandardCharsets.UTF_8);

        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        try (Connection conn = raw.newConnection(); Channel ch = conn.createChannel()) {
            ch.basicPublish("", queue, null, completion);
            // Контейнерные ретраи при sustained-перегрузке: переиграли дважды,
            // перегрузка не ушла — воркер сдаётся финальным reject без requeue.
            for (int i = 0; i < 2; i++) {
                GetResponse got = ch.basicGet(queue, false);
                assertThat(got).as("completion consumable, retry %d", i).isNotNull();
                assertThat(got.getBody()).as("body intact across redeliveries").isEqualTo(completion);
                ch.basicReject(got.getEnvelope().getDeliveryTag(), true);
            }
            GetResponse last = ch.basicGet(queue, false);
            assertThat(last).as("completion consumable for the final attempt").isNotNull();
            ch.basicReject(last.getEnvelope().getDeliveryTag(), false);

            assertThat(ch.basicGet(queue, true))
                .as("main queue drained — nothing redelivered forever").isNull();

            // Dead-letter hop — внутренний переход брокера, не в FIFO-потоке
            // канала: парковка может опоздать на мс — ждём дедлайном, не сном.
            // Потеря (нет DLX) → таймаут → чистый RED, не флейк.
            GetResponse parked = Awaitility.await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> ch.basicGet(dlq, false), g -> g != null);
            assertThat(parked.getBody()).as("parked body intact — completion not lost").isEqualTo(completion);
            assertThat(ch.basicGet(dlq, true))
                .as("DLQ holds exactly one copy").isNull();
        }
    }

    @Test
    void ackedCompletion_neverTouchesDlq() throws Exception {
        byte[] good = ("{\"serviceTaskId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")
            .getBytes(StandardCharsets.UTF_8);

        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        try (Connection conn = raw.newConnection(); Channel ch = conn.createChannel()) {
            ch.basicPublish("", queue, null, good);
            GetResponse got = ch.basicGet(queue, false);
            assertThat(got).isNotNull();
            ch.basicAck(got.getEnvelope().getDeliveryTag(), false);
            assertThat(ch.basicGet(dlq, true))
                .as("ACKed completion must not reach the DLQ").isNull();
        }
    }
}
