package com.zorrodev.bpm.rest.resource;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.exchange.JobDetailModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-66 критерии 1 (engine-сторона): самовосстановление топологии job-очередей
 * после потери на брокере — БЕЗ рестарта приложения.
 *
 * <p>Сценарий инцидента 2026-10-07: брокер пересоздан, очереди
 * {@code zorrobpm.jobs.*} пропали, {@code JobQueueDeclarer.declared} помнит
 * объявленное → send-путь declare пропускает (no-op) → публикации уходят в
 * несуществующую очередь (NO_ROUTE/return) и никогда не восстанавливаются.
 *
 * <p>Тест 1 (send-путь): очередь удалена вручную → следующая отправка
 * незамаршрутизируема → очередь возвращается сама, запись публикуется.
 * Тест 2 (reconnect): очередь удалена → физическое соединение сброшено
 * (как после пересоздания брокера) → новое соединение переобъявляет
 * известные очереди.
 *
 * <p>На master оба КРАСНЫЕ (переобъявления нет — очередь не возвращается).
 * Запуск: свой брокер + точечный failsafe (см. отчёт WO-REL-66).
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}",
    "spring.rabbitmq.publisher-confirm-type=correlated",
    "spring.rabbitmq.publisher-returns=true"
})
class RabbitTopologySelfHealIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxBatchProcessor outboxBatchProcessor;
    @Autowired private CachingConnectionFactory connectionFactory;

    private String host;
    private int port;
    private String user;
    private String password;

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
        outboxRepository.deleteAllInBatch();
    }

    @Test
    void sendToDeletedQueue_queueReturnsWithoutRestartAndEntryGetsPublished() throws Exception {
        String job = "rel66heal" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "zorrobpm.jobs." + job;

        // Очередь появляется через штатный send-путь (declare при отправке).
        OutboxEntry first = insertServiceTask(job);
        outboxBatchProcessor.processBatch();
        awaitPublished(first.getId());
        assertThat(queueExists(queue)).as("очередь объявлена send-путём").isTrue();

        // Инцидент: очередь пропала на брокере (пересоздание).
        deleteQueue(queue);
        assertThat(queueExists(queue)).as("очередь удалена").isFalse();

        // Следующая отправка незамаршрутизируема (return → попытка засчитана).
        OutboxEntry second = insertServiceTask(job);
        outboxBatchProcessor.processBatch();
        awaitAttempts(second.getId());

        // WO-REL-66: очередь возвращается сама, без рестарта приложения.
        await().atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(queueExists(queue))
                .as("очередь переобъявлена после потери, без рестарта")
                .isTrue());

        // Повторный прогон поллера доставляет запись (штатный ресенд pending-строки).
        outboxBatchProcessor.processBatch();
        awaitPublished(second.getId());
    }

    @Test
    void reconnectAfterQueueLoss_redeclaresKnownQueues() throws Exception {
        String job = "rel66rec" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "zorrobpm.jobs." + job;

        OutboxEntry first = insertServiceTask(job);
        outboxBatchProcessor.processBatch();
        awaitPublished(first.getId());
        assertThat(queueExists(queue)).as("очередь объявлена").isTrue();

        // Инцидент + новое физическое соединение (как после --force-recreate брокера).
        deleteQueue(queue);
        connectionFactory.resetConnection();
        connectionFactory.createConnection();

        // WO-REL-66: переподключение переобъявляет известные очереди.
        await().atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(queueExists(queue))
                .as("reconnect переобъявил известную очередь")
                .isTrue());
    }

    private OutboxEntry insertServiceTask(String job) throws Exception {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(UUID.randomUUID());
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob(job);
        detail.setVariables(java.util.Map.of());
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.SERVICE_TASK);
        entry.setPayload(objectMapper.writeValueAsString(detail));
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        return outboxRepository.save(entry);
    }

    private void awaitPublished(UUID id) {
        await().atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(
                outboxRepository.findById(id).orElseThrow().isPublished())
                .as("запись опубликована (broker ACK)")
                .isTrue());
    }

    private void awaitAttempts(UUID id) {
        await().atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(
                outboxRepository.findById(id).orElseThrow().getAttempts() >= 1)
                .as("незамаршрутизируемая отправка засчитала попытку")
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

    private void deleteQueue(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            ch.queueDelete(queue);
        }
    }
}
