package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PgItIsolation;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * <p>WO-QW-13 (isolation): every query here is scoped to the rows THIS test inserted, and the class
 * no longer wipes the shared timer tables in {@code @BeforeEach}. The whole {@code @Tag("pg")} suite
 * shares one PostgreSQL container whose cached contexts keep {@code TimerScheduler} polling every 5 s
 * (see {@link PgItIsolation}), so a whole-table assertion counts rows this class never created — that
 * is exactly how {@code notDueTimer_notInFindDue} went red on unchanged code in pipelines 175956 and
 * 175964. Asserting only about own ids removes the dependency on shared state entirely.
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

    /**
     * Rows this class inserted, removed in {@code @AfterEach} — WO-QW-13's own rule applied to itself:
     * a class that stops wiping the whole table must stop leaving crumbs in it. Measured: with the old
     * whole-table {@code @BeforeEach} wipe gone and no cleanup, this class left 2 committed due
     * {@code timer_jobs} rows behind (@verifier round 4 finding 6).
     *
     * <p>{@code @AfterEach} and not {@code @BeforeEach}: this deliberately touches only its OWN ids, so
     * it can never destroy a neighbour's fixture — which is what the removed whole-table wipe used to do
     * to everyone else in the suite.
     */
    private final List<UUID> insertedTimerJobIds = new ArrayList<>();
    private final List<UUID> insertedTimerStartJobIds = new ArrayList<>();

    @AfterEach
    void removeOwnTimerRows() {
        if (!insertedTimerJobIds.isEmpty()) {
            List<UUID> ids = List.copyOf(insertedTimerJobIds);
            insertedTimerJobIds.clear();
            PgItIsolation.deleteTimerJobs(jdbc, ids);
        }
        if (!insertedTimerStartJobIds.isEmpty()) {
            // timer_start_jobs too: an armed due start timer is picked up by a live TimerBatchProcessor
            // of ANOTHER cached context, which then fails on fk_process_instances__process_definition_id
            // — the very FK noise this WO removes. Measured: +1 due row per run without this (@verifier r5).
            List<UUID> ids = List.copyOf(insertedTimerStartJobIds);
            insertedTimerStartJobIds.clear();
            PgItIsolation.deleteTimerStartJobs(jdbc, ids);
        }
    }

    /** Inserts a catch timer and remembers its id for {@link #removeOwnTimerRows()}. */
    private UUID ownCatchTimerJob(UUID activityId, Instant dueAt) {
        UUID id = PgItIsolation.insertCatchTimerJob(jdbc, UUID.randomUUID(), activityId, dueAt);
        insertedTimerJobIds.add(id);
        return id;
    }

    /**
     * WO-QW-13: park the background poller — this class drives its own threads against the timer
     * tables, so it must not also have the poller rewriting them every few seconds. That is why it
     * needs no {@code @BeforeEach} wipe of the shared tables at all.
     *
     * <p>Parking is not total, and the assertions do not pretend otherwise: a freshly built context
     * ticks once immediately ({@code @Scheduled} without {@code initialDelay}), and neighbouring
     * contexts are not parked. Hence every assertion here asks about THIS test's own ids instead of
     * about the size of a shared table — see {@link PgItIsolation#ownRowsAmong}.
     */
    @DynamicPropertySource
    static void parkBackgroundTimerPollers(DynamicPropertyRegistry registry) {
        PgItIsolation.parkBackgroundTimerPollers(registry);
    }

    // ==================== GREEN: FOR UPDATE SKIP LOCKED — only one thread gets the row ====================

    @Test
    void timerJob_skipLocked_oneThreadGetsRow() throws Exception {
        // One due timer job, inserted by this test. Both threads look at THIS id only: the SKIP LOCKED
        // guarantee under test is about one row, and a foreign due row from another class would
        // otherwise change the counts (the old whole-table form counted the whole shared table).
        UUID jobId = ownCatchTimerJob(UUID.randomUUID(), Instant.now().minusSeconds(10));

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
                        "SELECT id FROM timer_jobs WHERE id = ? AND fired = false AND due_at <= now() FOR UPDATE",
                        UUID.class, jobId);
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
                        "SELECT id FROM timer_jobs WHERE id = ? AND fired = false AND due_at <= now() FOR UPDATE SKIP LOCKED",
                        UUID.class, jobId);
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
        insertedTimerStartJobIds.add(jobId);

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
                        "SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false AND due_at <= now() FOR UPDATE",
                        UUID.class, jobId);
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
                        "SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false AND due_at <= now() FOR UPDATE SKIP LOCKED",
                        UUID.class, jobId);
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
        UUID jobId = ownCatchTimerJob(UUID.randomUUID(), Instant.now().minusSeconds(10));

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
                    "SELECT COUNT(*) FROM timer_jobs WHERE id = ? AND fired = false AND due_at <= now()",
                    Integer.class, jobId);
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
                    "SELECT COUNT(*) FROM timer_jobs WHERE id = ? AND fired = false AND due_at <= now()",
                    Integer.class, jobId);
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

    /**
     * A timer that is not due yet is never a claim candidate — while a timer that IS due is.
     *
     * <p>WO-QW-13: the old form asserted that the scheduler query returns an EMPTY list, i.e. it asserted a
     * property of the whole shared {@code timer_jobs} table. Any due row left behind by another class — or
     * written by the live background poller between this test's insert and its query — made it fail with
     * {@code Expecting empty but was: [<uuid>]} on a row it never created.
     *
     * <p>The candidate set comes from the PRODUCTION query {@link TimerJobRepository#findDueLocked}
     * (not from a copy of its WHERE clause): this test decides what it owns, the engine decides what is
     * claimable, and the assertion asks about the intersection. So breaking the production predicate breaks
     * this test, and both halves of the criterion are pinned: the not-due row is absent, the due row of the
     * same pair is present — it cannot degenerate into "nothing is ever returned".
     */
    @Test
    @Transactional
    void notDueTimer_notInFindDue() {
        Instant now = Instant.now();
        UUID futureJobId = ownCatchTimerJob(UUID.randomUUID(), now.plusSeconds(3600));
        UUID dueJobId = ownCatchTimerJob(UUID.randomUUID(), now.minusSeconds(10));

        List<UUID> claimedByEngine = timerJobRepository.findDueLocked(now, 100).stream()
            .map(TimerJobEntity::getId)
            .toList();
        List<UUID> claimedByMe = PgItIsolation.ownRowsAmong(List.of(futureJobId, dueJobId), claimedByEngine);

        assertThat(claimedByMe).containsExactly(dueJobId);
        assertThat(claimedByMe).doesNotContain(futureJobId);
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
            ownCatchTimerJob(UUID.randomUUID(), now);
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
        Set<UUID> ownIds = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) {
            ownIds.add(ownCatchTimerJob(UUID.randomUUID(), now));
        }

        // WO-QW-13: this test's verdict must rest on ITS OWN rows. The production query returns whatever
        // is claimable table-wide (that is the scheduler's job), so the assertion narrows the answer to
        // this test's ids and claims only those. `totalProcessed` used to add up `batch.size()`, which
        // silently counted rows another class had inserted — the actual order dependency here.
        int totalProcessedOwn = 0;
        int runs = 0;
        while (true) {
            List<UUID> batch = timerJobRepository.findDueLocked(now, 100).stream()
                .map(TimerJobEntity::getId)
                .toList();
            assertThat(batch).as("run %s must not exceed the 100-row batch limit", runs)
                .hasSizeLessThanOrEqualTo(100);
            List<UUID> ownBatch = PgItIsolation.ownRowsAmong(ownIds, batch);
            if (ownBatch.isEmpty()) {
                break;
            }
            for (UUID id : ownBatch) {
                timerJobRepository.claimTimerJob(id);
                totalProcessedOwn++;
            }
            runs++;
            assertThat(runs).as("500 timers at batchSize=100 must drain, not loop forever")
                .isLessThanOrEqualTo(20);
        }

        assertThat(totalProcessedOwn).as("every timer of this test must be processed exactly once")
            .isEqualTo(500);
        assertThat(runs).as("500 timers at batchSize=100 need at least 5 runs").isGreaterThanOrEqualTo(5);
    }

    /**
     * Criterion #3: Existing behavior for N ≤ batchSize unchanged.
     * 50 timers, batchSize=100 → all 50 returned in one batch.
     */
    @Test
    @Transactional
    void batchLimit_fewerThanBatchSize_allReturned() {
        Instant now = Instant.now().minusSeconds(10);
        List<UUID> ownIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ownIds.add(ownCatchTimerJob(UUID.randomUUID(), now));
        }

        // "All 50 come back in one batch" asked about THIS test's 50 ids — the old `hasSize(50)`
        // decided the verdict from the size of a shared table (WO-QW-13).
        //
        // Residual dependency, stated rather than hidden (@verifier round 6): the window is global
        // (`ORDER BY due_at ASC LIMIT 100`), so if 50+ foreign due rows sort ahead of ours, the engine
        // legitimately returns those and this fails. That is strictly weaker than master (where ANY
        // foreign due row broke it) and it cannot be removed without weakening the criterion itself —
        // the batch limit is global by design, so asking about the global batch is the only honest way
        // to prove the limit.
        List<TimerJobEntity> batch = timerJobRepository.findDueLocked(now, 100);
        assertThat(batch).as("every own timer must come back in one batch; a foreign row sorting ahead of "
            + "ours in the global LIMIT 100 window is the only way this can fail")
            .extracting(TimerJobEntity::getId).containsAll(ownIds);
    }

    /**
     * Same batch-limit tests for timer_start_jobs table.
     */
    @Test
    @Transactional
    void batchLimit_timerStartJobs_returnsAtMostBatchSize() {
        Instant now = Instant.now().minusSeconds(10);
        for (int i = 0; i < 200; i++) {
            UUID startJobId = UUID.randomUUID();
            insertedTimerStartJobIds.add(startJobId);
            jdbc.update(
                "INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at, fired, created_at) " +
                "VALUES (?, 'test-proc', ?, 'start1', ?, false, ?)",
                startJobId, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(Instant.now()));
        }

        List<?> batch = timerStartJobRepository.findDueLocked(now, 100);
        assertThat(batch).hasSize(100);
    }
}
