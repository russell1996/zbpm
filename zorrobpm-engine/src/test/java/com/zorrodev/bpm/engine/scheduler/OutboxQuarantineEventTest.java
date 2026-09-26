package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-22 (B3): the FIRST transition of an outbox row to FAILED emits one
 * {@code outbox.quarantined} domain event (events table + DOMAIN_EVENT outbox row
 * for the {@code zorrobpm.events} exchange) — and a repeated mark emits nothing.
 *
 * <p>NOT run locally here (no Docker/PG in this env) — H2 part of the suite;
 * PG equivalence is covered by the redrive PgIT flow, full PG suite runs at review.
 */
@ActiveProfiles("test")
@SpringBootTest
class OutboxQuarantineEventTest {

    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private OutboxBatchProcessor processor;
    @Autowired
    private DomainEventRepository domainEventRepository;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAllInBatch();
        domainEventRepository.deleteAllInBatch();
    }

    private UUID seedPoisonEmail(int attempts) {
        UUID id = UUID.randomUUID();
        OutboxEntry e = new OutboxEntry();
        e.setId(id);
        e.setKind(OutboxKind.EMAIL);
        e.setPayload("{invalid json!!!");
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        e.setAttempts(attempts);
        e.setStatus(com.zorrodev.bpm.engine.entity.OutboxStatus.PENDING);
        outboxRepository.saveAndFlush(e);
        return id;
    }

    private List<DomainEventEntity> quarantinedEvents() {
        return domainEventRepository.findAll().stream()
            .filter(e -> "outbox.quarantined".equals(e.getType()))
            .toList();
    }

    private List<OutboxEntry> quarantinedOutboxRows() {
        return outboxRepository.findAll().stream()
            .filter(e -> e.getKind() == OutboxKind.DOMAIN_EVENT
                && e.getPayload() != null
                && e.getPayload().contains("outbox.quarantined"))
            .toList();
    }

    @Test
    void firstTransitionToFailed_emitsQuarantinedEvent() {
        // maxRetries default is 5 → attempts=4 + one more failure = quarantine.
        UUID id = seedPoisonEmail(4);

        processor.processBatch();

        assertThat(outboxRepository.findById(id)).isPresent();
        assertThat(outboxRepository.findById(id).get().getStatus()).isEqualTo(com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED);
        assertThat(quarantinedEvents()).as("one outbox.quarantined domain event").hasSize(1);
        assertThat(quarantinedOutboxRows()).as("one DOMAIN_EVENT outbox row for the exchange").hasSize(1);
        assertThat(quarantinedEvents().get(0).getData().toString()).contains(id.toString());
    }

    @Test
    @Transactional
    void repeatedMarkFailed_doesNotEmitTwice() {
        UUID id = seedPoisonEmail(4);

        processor.processBatch();
        assertThat(quarantinedEvents()).hasSize(1);

        // A duplicate mark (e.g. an in-flight delivery result landing after quarantine):
        // conditional SQL makes it a no-op (returns 0)…
        assertThat(outboxRepository.markFailed(id)).as("second mark is a no-op").isZero();

        // …and re-running the batch over the quarantined row emits nothing more
        // (the poller skips FAILED rows by construction).
        processor.processBatch();
        assertThat(quarantinedEvents()).as("still exactly one quarantined event").hasSize(1);
        assertThat(quarantinedOutboxRows()).hasSize(1);
    }
}
