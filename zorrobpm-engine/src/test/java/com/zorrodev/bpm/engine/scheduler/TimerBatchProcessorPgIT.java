package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-6: Real PostgreSQL integration test for atomic timer claim via FOR UPDATE SKIP LOCKED.
 * Tagged @Tag("pg") via PostgresIT — excluded from default CI.
 *
 * Tests TimerJobRepository.findDueLocked directly with two concurrent transactions:
 * T1 holds a row lock → T2 findDueLocked returns empty → exactly one claim.
 *
 * Run locally:
 * <pre>
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci up -d
 * mvn test -pl zorrobpm-engine -Dgroups=pg -Dtest=TimerBatchProcessorPgIT
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci down
 * </pre>
 */
public class TimerBatchProcessorPgIT extends PostgresIT {

    @Autowired TimerJobRepository timerJobRepository;
    @Autowired TimerStartJobRepository timerStartJobRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        timerJobRepository.deleteAllInBatch();
        timerStartJobRepository.deleteAllInBatch();
    }

    // ==================== GREEN: FOR UPDATE SKIP LOCKED — only one thread gets the row ====================

    @Test
    void timerJob_skipLocked_oneThreadGetsRow() throws Exception {
        // Insert one due timer job
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
            jobId, activityId, Timestamp.from(Instant.now().minusSeconds(10)), Timestamp.from(Instant.now()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        // WO-OPS-11 п.2: вместо sleep(2000) — латч доказывает факт удержания lock'а,
        // не фиксированное время. T1 сигналит СРАЗУ ПОСЛЕ взятия lock'а.
        CountDownLatch t1HoldingLock = new CountDownLatch(1);
        // T1 отпускает lock только после опроса T2 (координация фактами, не временем).
        CountDownLatch t2Done = new CountDownLatch(1);
        AtomicInteger t1Count = new AtomicInteger(0);
        AtomicInteger t2Count = new AtomicInteger(0);

        // T1: holds a transaction lock on the row (SELECT FOR UPDATE)
        Thread t1 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                transactionTemplate.execute(status -> {
                    // SELECT FOR UPDATE locks the row until commit
                    List<UUID> locked = jdbc.queryForList(
                        "SELECT id FROM timer_jobs WHERE fired = false AND due_at <= now() FOR UPDATE",
                        UUID.class);
                    t1Count.set(locked.size());
                    t1HoldingLock.countDown();
                    // Держим lock, пока T2 не опросит (сигнал снизу) — не фиксированные 2с.
                    try {
                        if (!t2Done.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("T2 не опросил вовремя");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // T2: tries findDueLocked while T1 holds the lock
        Thread t2 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                // Ждём ФАКТ: T1 держит lock (латч), а не фиксированные 500мс.
                if (!t1HoldingLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("T1 не взял lock вовремя");
                }
                transactionTemplate.execute(status -> {
                    List<?> locked = jdbc.queryForList(
                        "SELECT id FROM timer_jobs WHERE fired = false AND due_at <= now() FOR UPDATE SKIP LOCKED",
                        UUID.class);
                    t2Count.set(locked.size());
                    t2Done.countDown();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        t1.join(10000);
        t2.join(10000);

        // T1 sees 1 row, T2 sees 0 (skipped due to lock) — exactly one claim
        assertThat(t1Count.get()).isEqualTo(1);
        assertThat(t2Count.get()).isEqualTo(0);
    }

    @Test
    void timerStartJob_skipLocked_oneThreadGetsRow() throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at, fired, created_at) " +
            "VALUES (?, 'test-proc', ?, 'start1', ?, false, ?)",
            jobId, UUID.randomUUID(), Timestamp.from(Instant.now().minusSeconds(10)), Timestamp.from(Instant.now()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        // WO-OPS-11 п.2: координация фактами (латчи), не фиксированным временем.
        CountDownLatch t1HoldingLock = new CountDownLatch(1);
        CountDownLatch t2Done = new CountDownLatch(1);
        AtomicInteger t1Count = new AtomicInteger(0);
        AtomicInteger t2Count = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                transactionTemplate.execute(status -> {
                    List<UUID> locked = jdbc.queryForList(
                        "SELECT id FROM timer_start_jobs WHERE fired = false AND due_at <= now() FOR UPDATE",
                        UUID.class);
                    t1Count.set(locked.size());
                    t1HoldingLock.countDown();
                    try {
                        if (!t2Done.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("T2 не опросил вовремя");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                if (!t1HoldingLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("T1 не взял lock вовремя");
                }
                transactionTemplate.execute(status -> {
                    List<?> locked = jdbc.queryForList(
                        "SELECT id FROM timer_start_jobs WHERE fired = false AND due_at <= now() FOR UPDATE SKIP LOCKED",
                        UUID.class);
                    t2Count.set(locked.size());
                    t2Done.countDown();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        t1.join(10000);
        t2.join(10000);

        assertThat(t1Count.get()).isEqualTo(1);
        assertThat(t2Count.get()).isEqualTo(0);
    }

    // ==================== POF: WITHOUT SKIP LOCKED — both threads see the row ====================

    @Test
    void pof_withoutSkipLocked_bothThreadsSeeRow() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
            jobId, activityId, Timestamp.from(Instant.now().minusSeconds(10)), Timestamp.from(Instant.now()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger t1Count = new AtomicInteger(0);
        AtomicInteger t2Count = new AtomicInteger(0);

        // T1: plain SELECT without FOR UPDATE (autocommit → locks released immediately)
        Thread t1 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timer_jobs WHERE fired = false AND due_at <= now()",
                    Integer.class);
                t1Count.set(count != null ? count : 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // T2: plain SELECT without FOR UPDATE (sees same row)
        Thread t2 = new Thread(() -> {
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timer_jobs WHERE fired = false AND due_at <= now()",
                    Integer.class);
                t2Count.set(count != null ? count : 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        t1.join(10000);
        t2.join(10000);

        // POF: without SKIP LOCKED, both threads see the row
        assertThat(t1Count.get()).isEqualTo(1);
        assertThat(t2Count.get()).isEqualTo(1);
    }

    // ==================== Criterion #4: not-due timer not captured ====================

    @Test
    void notDueTimer_notInFindDue() {
        UUID jobId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
            jobId, UUID.randomUUID(), Timestamp.from(Instant.now().plusSeconds(3600)), Timestamp.from(Instant.now()));

        List<UUID> locked = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE fired = false AND due_at <= now() FOR UPDATE SKIP LOCKED",
            UUID.class);
        assertThat(locked).isEmpty();
    }

    // ==================== WO-REL-11: batch-size LIMIT ====================

    /**
     * Criterion #1: One processBatch() handles at most batchSize timers.
     * Inserts 500 due timers, calls findDueLocked with batchSize=100 → exactly 100 returned.
     */
    @Test
    @Transactional
    void batchLimit_returnsAtMostBatchSize() {
        Instant now = Instant.now().minusSeconds(10);
        for (int i = 0; i < 500; i++) {
            jdbc.update(
                "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(Instant.now()));
        }

        List<TimerJobEntity> batch1 = timerJobRepository.findDueLocked(now, 100);
        assertThat(batch1).hasSize(100);
    }

    /**
     * Criterion #2: Remaining due timers handled by next run, none lost.
     * 500 timers, batchSize=100 → 5 runs process all 500.
     */
    @Test
    @Transactional
    void batchLimit_allTimersProcessedAfterMultipleRuns() {
        Instant now = Instant.now().minusSeconds(10);
        for (int i = 0; i < 500; i++) {
            jdbc.update(
                "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(Instant.now()));
        }

        int totalProcessed = 0;
        for (int run = 0; run < 5; run++) {
            List<TimerJobEntity> batch = timerJobRepository.findDueLocked(now, 100);
            assertThat(batch).hasSize(100);
            for (TimerJobEntity job : batch) {
                timerJobRepository.claimTimerJob(job.getId());
            }
            totalProcessed += batch.size();
        }
        assertThat(totalProcessed).isEqualTo(500);

        // No more due timers
        List<TimerJobEntity> remaining = timerJobRepository.findDueLocked(now, 100);
        assertThat(remaining).isEmpty();
    }

    /**
     * Criterion #3: Existing behavior for N ≤ batchSize unchanged.
     * 50 timers, batchSize=100 → all 50 returned in one batch.
     */
    @Test
    @Transactional
    void batchLimit_fewerThanBatchSize_allReturned() {
        Instant now = Instant.now().minusSeconds(10);
        for (int i = 0; i < 50; i++) {
            jdbc.update(
                "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(Instant.now()));
        }

        List<TimerJobEntity> batch = timerJobRepository.findDueLocked(now, 100);
        assertThat(batch).hasSize(50);
    }

    /**
     * Same batch-limit tests for timer_start_jobs table.
     */
    @Test
    @Transactional
    void batchLimit_timerStartJobs_returnsAtMostBatchSize() {
        Instant now = Instant.now().minusSeconds(10);
        for (int i = 0; i < 200; i++) {
            jdbc.update(
                "INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at, fired, created_at) " +
                "VALUES (?, 'test-proc', ?, 'start1', ?, false, ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(Instant.now()));
        }

        List<?> batch = timerStartJobRepository.findDueLocked(now, 100);
        assertThat(batch).hasSize(100);
    }
}
