package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.OutboxStatus;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-66 критерий 3: разрыв самопитающейся петли {@code outbox.quarantined}.
 *
 * <p>Механика петли на master (инцидент 2026-10-07, ~24 записи/мин): доменное
 * событие, которому не на что маршрутизироваться (нет привязанной очереди на
 * {@code zorrobpm.events}), возвращается брокером (NO_ROUTE) → запись уходит в
 * карантин → карантин эмитит {@code outbox.quarantined} → оно ТОЖЕ
 * незамаршрутизируемо → ещё карантин → ещё событие. Счётчик карантина растёт
 * безостановочно.
 *
 * <p>Ожидаемое поведение: уведомление о карантине, которое само не
 * доставлено, дропается с учётом (WARN + метрика
 * {@code domain.event.unroutable{type="outbox.quarantined"}}) и НЕ
 * карантинится рекурсивно — счётчик карантина стабилен.
 *
 * <p>На master КРАСНЫЙ (карантин растёт каскадом). Запуск: свой брокер +
 * точечный failsafe (см. отчёт WO-REL-66).
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}",
    "spring.rabbitmq.publisher-confirm-type=correlated",
    "spring.rabbitmq.publisher-returns=true",
    // Быстрее к терминалу: 3 неудачи вместо 5 (механика та же).
    "zorrobpm.outbox.max-retries=3"
})
class QuarantineLoopBreakIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxBatchProcessor outboxBatchProcessor;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
    }

    @Test
    void unroutableQuarantineNotification_doesNotGrowQuarantine() throws Exception {
        // Доменное событие без привязки на exchange → NO_ROUTE.
        OutboxEntry seed = new OutboxEntry();
        seed.setId(UUID.randomUUID());
        seed.setKind(OutboxKind.DOMAIN_EVENT);
        seed.setPayload(objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "sequence", 1,
            "type", "rel66.unroutable",
            "data", Map.of()
        )));
        seed.setCreatedAt(Instant.now());
        seed.setPublished(false);
        outboxRepository.save(seed);

        // Дожимаем seed до карантина (3 неудачи → FAILED + emit outbox.quarantined).
        driveUntilQuarantined();
        long quarantinedAfterSeed = outboxRepository.countQuarantined();
        assertThat(quarantinedAfterSeed)
            .as("seed докарантинен (ровно одна запись)")
            .isEqualTo(1);

        // Гоняем поллер дальше: уведомление о карантине тоже незамаршрутизируемо.
        // На master оно само уходит в карантин и эмитит следующее (каскад).
        for (int i = 0; i < 10; i++) {
            outboxBatchProcessor.processBatch();
            Thread.sleep(1000);
        }

        // WO-REL-66: каскада нет — счётчик карантина стабилен.
        assertThat(outboxRepository.countQuarantined())
            .as("карантин не растёт каскадом (петля разорвана)")
            .isEqualTo(quarantinedAfterSeed);

        // Учёт дропа: метрика незамаршрутизируемых уведомлений выросла.
        double dropped = meterRegistry
            .find("domain.event.unroutable").tag("type", "outbox.quarantined")
            .counter() == null ? 0.0 : meterRegistry
            .find("domain.event.unroutable").tag("type", "outbox.quarantined")
            .counter().count();
        assertThat(dropped)
            .as("дроп незамаршрутизируемого уведомления учтён метрикой")
            .isGreaterThanOrEqualTo(1.0);
    }

    /** Крутит processBatch, пока хотя бы одна запись не уйдёт в карантин. */
    private void driveUntilQuarantined() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            outboxBatchProcessor.processBatch();
            if (quarantinedCount() > 0) {
                return;
            }
            Thread.sleep(400);
        }
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(quarantinedCount())
                .as("seed докарантинен")
                .isGreaterThanOrEqualTo(1L));
    }

    private long quarantinedCount() {
        return outboxRepository.findAll().stream()
            .filter(e -> e.getStatus() == OutboxStatus.FAILED)
            .count();
    }
}
