package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PROC-7: Retroactive catch tests on real PostgreSQL.
 * Tagged @Tag("pg") — excluded from default CI runs (excludedGroups=pg in failsafe).
 * Run locally against docker-compose postgres:16:
 *
 * <pre>
 * docker compose up -d postgres
 * mvn test -pl zorrobpm-engine -Dgroups=pg \
 *   -Dspring.profiles.active=test,pgtest \
 *   -DPG_HOST=localhost -DPG_PORT=5433 -DPG_DB=zbpm_test -DPG_USER=test -DPG_PASSWORD=test
 * docker compose down postgres
 * </pre>
 *
 * criterion #3 (MT-10): audit filter query on PG — before MT-10 fix, the JPA Specification
 *   with null params generated SQL incompatible with PostgreSQL → 500 in prod.
 * criterion #4 (REL-4 L2): FOR UPDATE SKIP LOCKED on PG — proves two concurrent pollers
 *   don't pick the same outbox entries. H2 doesn't enforce this the same way.
 */
public class RetroPgIT extends PostgresIT {

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired TransactionTemplate transactionTemplate;

    // ==================== Criterion #3: MT-10 audit filter on PG ====================

    @Test
    void auditFilter_withNullFilters_returnsAll() {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setId(UUID.randomUUID());
        entry.setAction("TEST_ACTION");
        entry.setPrincipalType("USER");
        entry.setPrincipalId(UUID.randomUUID().toString());
        entry.setAt(Instant.now());
        entry.setProcessKey("test-pg-proc");
        entry.setTargetId(UUID.randomUUID().toString());
        auditLogRepository.save(entry);

        // Before MT-10 fix: PG 500 (Specification null-param SQL incompatible).
        // After fix: Specification uses Criteria API → works on PG.
        List<AuditLogEntity> result = auditLogRepository.findByFilters(null, null, null, null);
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getAction()).isEqualTo("TEST_ACTION");
    }

    @Test
    void auditFilter_withProcessKeyFilter() {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setId(UUID.randomUUID());
        entry.setAction("FILTERED_ACTION");
        entry.setPrincipalType("USER");
        entry.setPrincipalId(UUID.randomUUID().toString());
        entry.setAt(Instant.now());
        entry.setProcessKey("specific-process");
        entry.setTargetId(UUID.randomUUID().toString());
        auditLogRepository.save(entry);

        List<AuditLogEntity> result = auditLogRepository.findByFilters("specific-process", null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProcessKey()).isEqualTo("specific-process");

        List<AuditLogEntity> empty = auditLogRepository.findByFilters("nonexistent", null, null, null);
        assertThat(empty).isEmpty();
    }

    // ==================== Criterion #4: REL-4 L2 FOR UPDATE SKIP LOCKED on PG ====================

    @Test
    void outbox_twoConcurrentPollers_noDuplicates() throws Exception {
        int entryCount = 10;
        for (int i = 0; i < entryCount; i++) {
            OutboxEntry entry = new OutboxEntry();
            entry.setId(UUID.randomUUID());
            entry.setPayload("{\"activityId\":\"act" + i + "\"}");
            entry.setPublished(false);
            entry.setCreatedAt(Instant.now());
            outboxRepository.save(entry);
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger poller1Count = new AtomicInteger(0);
        AtomicInteger poller2Count = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            try {
                ready.countDown();
                go.await();
                Integer count = transactionTemplate.execute(status -> {
                    List<OutboxEntry> picked = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
                    for (OutboxEntry e : picked) {
                        outboxRepository.markPublished(e.getId());
                    }
                    return picked.size();
                });
                poller1Count.set(count != null ? count : 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                ready.countDown();
                go.await();
                Integer count = transactionTemplate.execute(status -> {
                    List<OutboxEntry> picked = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
                    for (OutboxEntry e : picked) {
                        outboxRepository.markPublished(e.getId());
                    }
                    return picked.size();
                });
                poller2Count.set(count != null ? count : 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join(10000);
        t2.join(10000);

        // FOR UPDATE SKIP LOCKED: each entry picked by exactly one poller
        int totalPicked = poller1Count.get() + poller2Count.get();
        assertThat(totalPicked)
            .as("Two pollers must pick all entries without duplicates (FOR UPDATE SKIP LOCKED)")
            .isEqualTo(entryCount);

        List<OutboxEntry> remaining = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
        assertThat(remaining).isEmpty();
    }
}
