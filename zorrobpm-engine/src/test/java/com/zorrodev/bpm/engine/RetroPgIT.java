package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PROC-7 + WO-INT-1 + WO-AUD-1: Retroactive catch tests on real PostgreSQL.
 * Tagged @Tag("pg") via PostgresIT base class — excluded from default CI runs.
 *
 * Run locally:
 * <pre>
 * docker compose up -d postgres
 * mvn test -pl zorrobpm-engine -Dgroups=pg
 * docker compose down postgres
 * </pre>
 *
 * criterion #3 (MT-10): audit filter query on PG.
 * criterion #4 (REL-4 L2): FOR UPDATE SKIP LOCKED on PG.
 * AUD-1 #1: two concurrent pollers with @Transactional → no duplicates.
 * AUD-1 #2 (POF): without @Transactional (autocommit) → duplicates.
 */
public class RetroPgIT extends PostgresIT {

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired OutboxBatchProcessor outboxBatchProcessor;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
        auditLogRepository.deleteAllInBatch();
    }

    // ==================== WO-AUD-2: hot table indexes exist on PG ====================

    @Test
    void aud2_hotTableIndexesExist() {
        List<String> expectedIndexes = List.of(
            // activities
            "idx_activities_process_instance_status",
            "idx_activities_token_bpmn_element",
            "idx_activities_token_status",
            // outbox
            "idx_outbox_published_created_at",
            // user_tasks
            "idx_user_tasks_process_instance_id",
            "idx_user_tasks_process_definition_id",
            // service_tasks
            "idx_service_tasks_process_instance_id",
            "idx_service_tasks_process_definition_id",
            // incidents
            "idx_incidents_activity_id"
        );

        List<String> actualIndexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE 'idx_%'",
            String.class
        );

        for (String expected : expectedIndexes) {
            assertThat(actualIndexes)
                .as("Index %s must exist on PostgreSQL", expected)
                .contains(expected);
        }
    }

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

    // ==================== REL-4 L2: FOR UPDATE SKIP LOCKED on PG ====================

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
                    List<OutboxEntry> picked = outboxRepository.findPendingBatch(100);
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
                    List<OutboxEntry> picked = outboxRepository.findPendingBatch(100);
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

        int totalPicked = poller1Count.get() + poller2Count.get();
        assertThat(totalPicked)
            .as("Two pollers must pick all entries without duplicates (FOR UPDATE SKIP LOCKED)")
            .isEqualTo(entryCount);

        List<OutboxEntry> remaining = outboxRepository.findPendingBatch(100);
        assertThat(remaining).isEmpty();
    }

    // ==================== AUD-1 POF: WITHOUT @Transactional → duplicates (RED) ====================
    //
    // POF per standing-prompt §1b: demonstrates that without @Transactional, two concurrent
    // pollers process the same outbox entries (duplicates).
    //
    // Race mechanism: In autocommit mode, each SQL statement is its own transaction.
    // SELECT … FOR UPDATE acquires row locks for the statement duration, then commits → locks released.
    // markPublished runs in a separate autocommit transaction.
    //
    // Race window exploitation:
    //   T1: SELECT → gets 10 rows → autocommit commits → locks released (markPublished NOT yet)
    //   T2: SELECT → gets same 10 rows (no locks held, markPublished not done) → autocommit commits
    //   Both threads processed all 10 rows → totalPicked = 20 > 10 (duplicates!)
    //
    // With @Transactional (GREEN test above): locks held until outer TX commit → T2 SKIP → no dups.

    @Test
    void aud1_proofOfFailure_withoutTx_duplicatedPicks() throws Exception {
        int entryCount = 10;
        for (int i = 0; i < entryCount; i++) {
            OutboxEntry entry = new OutboxEntry();
            entry.setId(UUID.randomUUID());
            entry.setPayload("{\"activityId\":\"pof-act" + i + "\"}");
            entry.setPublished(false);
            entry.setCreatedAt(Instant.now());
            outboxRepository.save(entry);
        }
        outboxRepository.flush();

        // Barrier: both threads ready → T1 SELECT → T1 signals → T2 SELECT → both mark
        // This ensures T2's SELECT runs AFTER T1's SELECT commits (autocommit releases locks)
        // but BEFORE either thread calls markPublished (so T2 still sees published=false).
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch t1SelectDone = new CountDownLatch(1);
        AtomicInteger poller1Count = new AtomicInteger(0);
        AtomicInteger poller2Count = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            try {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                // SELECT in autocommit → locks acquired then released immediately on commit
                List<OutboxEntry> picked = outboxRepository.findPendingBatch(100);
                poller1Count.set(picked.size());
                t1SelectDone.countDown();
                // Wait for T2 to also SELECT before either marks
                t1SelectDone.await(5, TimeUnit.SECONDS);
                // markPublished in separate autocommit (too late — T2 already picked same rows)
                for (OutboxEntry e : picked) {
                    outboxRepository.markPublished(e.getId());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                // Wait for T1's SELECT to finish (autocommit committed → locks released)
                t1SelectDone.await(5, TimeUnit.SECONDS);
                // SELECT in autocommit → sees same rows (no locks, markPublished not done yet)
                List<OutboxEntry> picked = outboxRepository.findPendingBatch(100);
                poller2Count.set(picked.size());
                // markPublished (no-op on already-marked, but T2 "processed" them)
                for (OutboxEntry e : picked) {
                    outboxRepository.markPublished(e.getId());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);

        int totalPicked = poller1Count.get() + poller2Count.get();

        // WITHOUT @Transactional: T1 picks N, T2 picks N → total = 2N > N (duplicates!)
        assertThat(totalPicked)
            .as("POF AUD-1: without @Transactional, concurrent autocommit pollers produce DUPLICATED picks")
            .isGreaterThan(entryCount);
    }
}
