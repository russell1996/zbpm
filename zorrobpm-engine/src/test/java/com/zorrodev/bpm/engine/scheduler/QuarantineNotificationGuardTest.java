package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * WO-REL-66 (B): таблица {@link QuarantineNotificationGuard} —
 * что считается уведомлением о карантине, а что нет.
 */
class QuarantineNotificationGuardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String envelope(String type) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "sequence", 1,
            "type", type,
            "data", Map.of("outboxId", UUID.randomUUID().toString())));
    }

    @Test
    void table() throws Exception {
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.DOMAIN_EVENT, envelope("outbox.quarantined"), objectMapper))
            .as("карантинное уведомление распознаётся").isTrue();

        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.SERVICE_TASK, envelope("outbox.quarantined"), objectMapper))
            .as("тот же payload не-DOMAIN_EVENT — не уведомление").isFalse();
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.EMAIL, envelope("outbox.quarantined"), objectMapper))
            .as("EMAIL — не уведомление").isFalse();
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.DOMAIN_EVENT, envelope("process-instance.started"), objectMapper))
            .as("чужой тип события — не уведомление").isFalse();
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.DOMAIN_EVENT, "{not-json", objectMapper))
            .as("битый payload — не уведомление (в чужой poison-путь)").isFalse();
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.DOMAIN_EVENT, null, objectMapper))
            .as("null-payload — не уведомление").isFalse();
        assertThat(QuarantineNotificationGuard.isQuarantineNotification(
            OutboxKind.DOMAIN_EVENT, envelope("outbox.quarantined"), null))
            .as("null-mapper — не уведомление").isFalse();
    }

    @Test
    void dropUndeliverable_marksPublishedAndCountsMetric() throws Exception {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.DOMAIN_EVENT);
        entry.setPayload(envelope("outbox.quarantined"));
        entry.setCreatedAt(Instant.now());
        OutboxRepository repo = mock(OutboxRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);

        QuarantineNotificationGuard.dropUndeliverable(entry, repo, metrics);

        verify(repo).markPublished(entry.getId());
        assertThat(registry.get("zbpm.domain.event.unroutable")
            .tag("type", "outbox.quarantined").counter().count())
            .isEqualTo(1.0);
    }
}
