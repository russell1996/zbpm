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
 * {@code requeue=false} → брокер паркует тело в
 * {@code zorrobpm.complete-service-task.dlq}, а не роняет и не крутит вечно.
 * Возврат — вручную (Management UI → republish в основную очередь;
 * идемпотентность держат дедуп C8-36 + replay-guard A-NEW4-15 — см.
 * {@code docs/guides/integration-quickstart.md}, раздел DLQ).
 *
 * <p>Форма (G-N: очередь/DLX/DLQ/биндинг объявляет НАСТОЯЩИЙ прод-бин
 * {@code RabbitConfiguration}, а не копия его аргументов, — убери DLX-аргумент
 * в прод-коде и parked-тест уходит в RED таймаутом ожидания DLQ).
 * P-10: publish и consume на ОДНОМ канале (basicPublish асинхронен — get на
 * другом соединении гоняется с ним и видит пустую очередь).
 *
 * <p>Запуск: {@code RABBITMQ_PORT=5674 ...} + прогон failsafe {@code -Dgroups=rabbit}
 * (CI — {@code ci/run-rabbit-tests.sh}; модуль zorrobpm-rest входит в его -pl).
 */
@Tag("rabbit")
class CompleteOverloadDlqRabbitIT {

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory cf;
    private RabbitAdmin admin;

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
        // делает при старте движка), не копия аргументов.
        RabbitConfiguration prod = new RabbitConfiguration();
        admin.declareExchange(prod.completeServiceTaskDlx());
        admin.declareQueue(prod.completeServiceTaskQueue());
        admin.declareQueue(prod.completeServiceTaskDlq());
        admin.declareBinding(prod.completeServiceTaskDlqBinding());
        admin.purgeQueue(RabbitConfiguration.COMPLETE_QUEUE, false);
        try {
            admin.purgeQueue(RabbitConfiguration.COMPLETE_DLQ, false);
        } catch (Exception e) {
            // Осознанно: DLQ существует только потому, что её объявляет прод-бин.
            // Если прод-код перестанет её объявлять (POF-мутант), setup не должен
            // маскировать это под ошибку подготовки — parked-тест ниже обязан
            // упасть поведенчески (таймаут ожидания DLQ = сообщение потеряно).
        }
    }

    @AfterEach
    void teardown() {
        try {
            admin.deleteQueue(RabbitConfiguration.COMPLETE_QUEUE);
            admin.deleteQueue(RabbitConfiguration.COMPLETE_DLQ);
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
            ch.basicPublish("", RabbitConfiguration.COMPLETE_QUEUE, null, completion);
            // Контейнерные ретраи при sustained-перегрузке: переиграли дважды,
            // перегрузка не ушла — воркер сдаётся финальным reject без requeue.
            for (int i = 0; i < 2; i++) {
                GetResponse got = ch.basicGet(RabbitConfiguration.COMPLETE_QUEUE, false);
                assertThat(got).as("completion consumable, retry %d", i).isNotNull();
                assertThat(got.getBody()).as("body intact across redeliveries").isEqualTo(completion);
                ch.basicReject(got.getEnvelope().getDeliveryTag(), true);
            }
            GetResponse last = ch.basicGet(RabbitConfiguration.COMPLETE_QUEUE, false);
            assertThat(last).as("completion consumable for the final attempt").isNotNull();
            ch.basicReject(last.getEnvelope().getDeliveryTag(), false);

            assertThat(ch.basicGet(RabbitConfiguration.COMPLETE_QUEUE, true))
                .as("main queue drained — nothing redelivered forever").isNull();

            // Dead-letter hop — внутренний переход брокера, не в FIFO-потоке
            // канала: парковка может опоздать на мс — ждём дедлайном, не сном.
            // Потеря (нет DLX) → таймаут → чистый RED, не флейк.
            GetResponse parked = Awaitility.await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> ch.basicGet(RabbitConfiguration.COMPLETE_DLQ, false), g -> g != null);
            assertThat(parked.getBody()).as("parked body intact — completion not lost").isEqualTo(completion);
            assertThat(ch.basicGet(RabbitConfiguration.COMPLETE_DLQ, true))
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
            ch.basicPublish("", RabbitConfiguration.COMPLETE_QUEUE, null, good);
            GetResponse got = ch.basicGet(RabbitConfiguration.COMPLETE_QUEUE, false);
            assertThat(got).isNotNull();
            ch.basicAck(got.getEnvelope().getDeliveryTag(), false);
            assertThat(ch.basicGet(RabbitConfiguration.COMPLETE_DLQ, true))
                .as("ACKed completion must not reach the DLQ").isNull();
        }
    }
}
