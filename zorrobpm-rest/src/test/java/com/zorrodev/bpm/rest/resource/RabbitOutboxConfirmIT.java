package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.exchange.JobDetailModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * WO-REL-12 (R-02) — publisher confirms against a REAL RabbitMQ broker (not a mock).
 * Tagged @Tag("rabbit") and excluded from the standard build (see zorrobpm-rest/pom.xml
 * {@code zbpm.excludedGroups}); run locally against a running rabbitmq:3.13:
 *
 * <pre>
 * docker compose up -d rabbitmq
 * mvn -B verify -pl zorrobpm-rest -am -Dsurefire.skip=true \
 *     -Dgroups=rabbit -Dzbpm.excludedGroups=
 * </pre>
 *
 * Full production wiring: OutboxBatchProcessor → Spring event → rabbitmq listener →
 * RabbitTemplate.convertAndSend with CorrelationData(outboxId) → broker confirm/return →
 * OutboxDeliveryResult → OutboxDeliveryResultListener → markPublished (only after ACK).
 *
 * Criterion 3: unroutable message (no binding on the topic exchange) → broker returns/NACKs it →
 * entry stays published=false and counts a retry attempt.
 * Criterion 4: routable message (job queue declared by ServiceTaskListener) → broker ACKs →
 * entry becomes published=true — only via the confirm callback, never from the processor.
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    // WO-AUDIT-1: host/port env-driven (defaults = historic localhost:5672) so the
    // CI/local rabbit script (ci/run-rabbit-tests.sh, P-23 non-standard host port)
    // can point the test at its own broker without touching the test body.
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}",
    "spring.rabbitmq.publisher-confirm-type=correlated",
    "spring.rabbitmq.publisher-returns=true"
})
class RabbitOutboxConfirmIT {

    private static final long DEADLINE_MILLIS = 20_000;

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxBatchProcessor outboxBatchProcessor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
    }

    private OutboxEntry insertEntry(OutboxKind kind, String payload) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(kind);
        entry.setPayload(payload);
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        return outboxRepository.save(entry);
    }

    /** Polls a condition until it holds or the deadline expires (broker feedback is async). */
    private void awaitUntil(String what, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + what, e);
            }
        }
        fail("Timed out waiting for: " + what);
    }

    // ==================== Criterion 4: ACK → published=true only after broker confirmation ====================

    @Test
    void criterion4_ack_markPublishedOnlyAfterBrokerConfirmation() throws Exception {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(UUID.randomUUID());
        detail.setJob("rel12-confirm");
        OutboxEntry entry = insertEntry(OutboxKind.SERVICE_TASK,
            objectMapper.writeValueAsString(detail));

        // The processor publishes the event; ServiceTaskListener declares the job queue and
        // sends the message with CorrelationData(entry.id) → real broker ACKs → the
        // OutboxDeliveryResultListener marks the row published.
        outboxBatchProcessor.processBatch();

        awaitUntil("broker ACK to mark outbox entry published",
            () -> outboxRepository.findById(entry.getId()).orElseThrow().isPublished());
    }

    // ==================== Criterion 3: unroutable → stays pending, retry attempt counted ====================

    @Test
    void criterion3_unroutableDomainEvent_staysPendingAndCountsRetryAttempt() throws Exception {
        // Domain envelope with a routing key that matches NO binding on zorrobpm.events
        // (topic exchange) → broker returns it (mandatory) / NACKs → delivery failed.
        OutboxEntry entry = insertEntry(OutboxKind.DOMAIN_EVENT,
            objectMapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "type", "rel12.no-such-binding.event",
                "data", Map.of()
            )));

        outboxBatchProcessor.processBatch();

        // Broker feedback is async: wait until the failure is recorded, then assert the row
        // is NOT published and the retry attempt was counted (attempts >= 1).
        awaitUntil("delivery failure to be recorded on the outbox entry",
            () -> outboxRepository.findById(entry.getId()).orElseThrow().getAttempts() >= 1);

        OutboxEntry reloaded = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.isPublished())
            .as("unroutable entry must stay published=false (WO-REL-12 R-02)")
            .isFalse();
        assertThat(reloaded.getStatus()).isEqualTo("PENDING");
    }
}
