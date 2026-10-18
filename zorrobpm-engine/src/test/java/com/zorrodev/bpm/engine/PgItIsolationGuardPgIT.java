package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WO-QW-13 / WO-OPS-29: the invariant that keeps {@code test:pg} order-independent, proven instead of
 * assumed.
 *
 * <p>Both assertions below are the reason the two red tests of pipelines 175956 / 175964 are green
 * now: a PG test class shares one database with every other class AND with the live
 * {@code TimerScheduler} of every cached Spring context, so "the timer table is in the state my test
 * needs" is never true. What must be true is narrower and is what is asserted here:
 *
 * <ol>
 *   <li>a claim query scoped to the ids a test owns returns exactly those of the test's own rows,
 *       while the claim window demonstrably contains foreign rows (so the old whole-table
 *       {@code isEmpty()} assertion could not hold) — and the verdict is immune to a CONCURRENT
 *       claimer, which is what actually turned the test red in the shared suite (WO-OPS-29 round 2:
 *       a live neighbour claimed the test's own row between insert and claim, 1 of 3 full-suite
 *       runs red on f7456bd89);</li>
 *   <li>a timer-start row that is NOT the row this deployment created — the stale leftover shape
 *       that made {@code DeploymentAtomicityPgIT:146} report {@code expected: 1 but was: 0} — is
 *       never mistaken for the deployment's own job, and the deployment's own job is pinned to the
 *       instant the test deployed plus the BPMN's duration instead of to the database wall clock.</li>
 * </ol>
 *
 * <p><b>Self-cleaning.</b> {@code criterion1} runs inside the test transaction (that is the
 * protection, see its javadoc): every row it inserts rolls back with the test, so nothing of the
 * test's own survives. The writer thread's rows are committed (a separate thread on its own
 * autocommit connections) and are removed by id from {@link #removeRowsCreatedByCriterion1} in
 * {@code @AfterEach}.
 */
public class PgItIsolationGuardPgIT extends PostgresIT {

    /**
     * The verdict's claim window. The production poller's batch is 100 ({@code zorrobpm.timer.batch-size});
     * WO-OPS-29's first flake mechanism was eviction: with {@code ORDER BY due_at ASC LIMIT 100}, a
     * committed backlog of more than 100 due rows older than the test's own row (the shared table keeps
     * re-arming rows whose fire failed) pushes the own row out of the window. The LIMIT is not a
     * property under test — the id-scoping of the claim set is — so the window is widened 100x to keep
     * the own row inside it whatever the shared backlog is.
     */
    private static final int OPS29_CLAIM_WINDOW = 10_000;

    @Autowired JdbcTemplate jdbc;
    @Autowired TimerJobRepository timerJobRepository;
    @Autowired DBService dbService;

    /** Writer-thread rows (committed; the test-transaction rows roll back with the test). */
    private final List<UUID> rowsToClean = new ArrayList<>();

    /**
     * Cleanup runs HERE and NOT in the test transaction.
     *
     * <p>{@code criterion1}'s own rows (own due, own future, foreign leftover) roll back with the test
     * transaction — they never reach this method. What reaches it is the writer thread's committed
     * rows: a separate thread on its own autocommit connections, which the rollback cannot undo.
     * Removing them by id — never by a whole-table predicate — is the self-cleanup contract of
     * WO-QW-13; the {@code @AfterEach} here runs outside any test transaction, so the delete commits.
     */
    @AfterEach
    void removeRowsCreatedByCriterion1() {
        if (rowsToClean.isEmpty()) {
            return;
        }
        List<UUID> ids;
        synchronized (rowsToClean) {
            ids = List.copyOf(rowsToClean);
            rowsToClean.clear();
        }
        PgItIsolation.deleteTimerJobs(jdbc, ids);
    }

    /** The poller is parked so the guard measures the tests' own doing, not a racing scheduler. */
    @DynamicPropertySource
    static void parkBackgroundTimerPollers(DynamicPropertyRegistry registry) {
        PgItIsolation.parkBackgroundTimerPollers(registry);
    }

    /**
     * Criterion 1: the claim set of THIS test is immune to (a) a leftover due row, (b) a concurrent
     * committed writer and (c) a concurrent claimer — the live poller of another cached context,
     * reproduced here by a thread that drives the PRODUCTION select ({@link DBService#findDueTimerJobsLocked}
     * — the SKIP LOCKED lock window) and the PRODUCTION CAS claim ({@link DBService#claimTimerJob} —
     * the atomic fired=false→true a winning node commits) around the test's own row.
     *
     * <p><b>WO-OPS-29 round 2 — the protection.</b> Round 1 left the own row COMMITTED between insert
     * and claim; a live {@code TimerScheduler} of a neighbouring, fully-unparked context claimed it in
     * that window (measured on f7456bd89: 1 of 3 full-suite runs red, the maintainer's independent run). This
     * method is therefore {@code @Transactional}: every row it inserts — the own due row, the own
     * future row and the foreign leftover — is UNCOMMITTED until the test ends. Under READ COMMITTED no
     * other transaction (the in-test claimer, a real neighbour, anything else) can even SEE those rows,
     * let alone lock or claim them; the insert's own row lock plus the claim's {@code SKIP LOCKED}
     * close the lock-overlap window on top of that. The verdict is a function of this transaction's own
     * state — there is no timing in it at all. And the test's rows roll back with the transaction, so
     * the guard cannot be the source of the residue it exists to prevent.
     *
     * <p>The candidate set is produced by the PRODUCTION query {@link TimerJobRepository#findDueLocked}
     * (G-N: a test must judge the real query, not a copy of its WHERE clause) and then narrowed to this
     * test's own ids by {@link PgItIsolation#ownRowsAmong}. The verdict {@code containsExactly(ownDue)}
     * is unchanged from round 1 — not weakened.
     */
    @Test
    @Transactional
    void criterion1_claimSetScopedByOwnIds_ignoresForeignAndConcurrentlyWrittenDueRows() throws Exception {
        final int ops29WriterCap = 20;
        UUID ownFuture = UUID.randomUUID();
        UUID ownDue = UUID.randomUUID();

        // Inside the test transaction: uncommitted, and therefore invisible to every other transaction
        // until this test ends. Round 1 paid for the committed form: a live neighbour claimed exactly a
        // row like this between insert and claim.
        UUID foreignLeftover = PgItIsolation.insertCatchTimerJob(jdbc, UUID.randomUUID(),
            Instant.now());

        AtomicBoolean claimerRunning = new AtomicBoolean(true);
        AtomicReference<Throwable> claimerError = new AtomicReference<>();
        AtomicBoolean ownDueClaimedByClaimer = new AtomicBoolean();
        AtomicInteger postBarrierCycles = new AtomicInteger();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch claimerRacedAroundVerdict = new CountDownLatch(1);

        // The concurrent claimer, exactly like a TimerScheduler in another cached context. Every cycle is
        // the PRODUCTION short-transaction select — the SKIP LOCKED lock window that evicted round 1's
        // row under a concurrent tick — and, after the barrier below, the PRODUCTION CAS claim on our
        // own row: one attempt per cycle, the same atomic fired=false→true a winning node commits inside
        // its fire transaction. Both are the production methods (G-N): nothing here is a copy of the
        // WHERE clause or of the claim.
        Thread claimer = new Thread(() -> {
            while (claimerRunning.get()) {
                try {
                    dbService.findDueTimerJobsLocked(Instant.now(), 100);
                    if (inserted.await(10, TimeUnit.SECONDS)) {
                        if (!ownDueClaimedByClaimer.get() && dbService.claimTimerJob(ownDue)) {
                            ownDueClaimedByClaimer.set(true);
                        }
                        int cycles = postBarrierCycles.incrementAndGet();
                        if (cycles == 2) {
                            claimerRacedAroundVerdict.countDown();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    claimerError.set(t);
                }
            }
        });
        claimer.setDaemon(true);
        claimer.start();

        AtomicBoolean writerRunning = new AtomicBoolean(true);
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        // Concurrent: the writer thread appends every ~2 ms while the main thread reads. A bare
        // ArrayList would give a torn snapshot (or CME) in the very test whose job is to be a reliable
        // guard (reviewer r5 finding 5). CopyOnWriteArrayList makes List.copyOf() a consistent snapshot.
        List<UUID> writtenByWriter = new CopyOnWriteArrayList<>();
        // WO-OPS-11 pattern: coordinate by FACT, never by a fixed sleep — the writer signals after its
        // first committed insert, so the assertion below can never lose a race with thread start-up.
        CountDownLatch firstWrite = new CountDownLatch(1);
        AtomicReference<Instant> writerBase = new AtomicReference<>();

        // The concurrent writer (round 1, kept): committed foreign rows, each strictly AFTER the own
        // row's due instant (own due = now - 10s, foreign = base + 1s + i with base taken before now)
        // and capped, so "concurrently written" stays a fact while no foreign row can be due at the
        // claim instant — neither the claim query nor a live neighbour can pick the writer's rows up.
        Thread writer = new Thread(() -> {
            try {
                int written = 0;
                while (writerRunning.get() && written < ops29WriterCap) {
                    Instant base = writerBase.get();
                    if (base == null) {
                        Thread.sleep(2);
                        continue;
                    }
                    UUID id = PgItIsolation.insertCatchTimerJob(jdbc, UUID.randomUUID(),
                        base.plusSeconds(1L + written));
                    writtenByWriter.add(id);
                    synchronized (rowsToClean) {
                        rowsToClean.add(id);
                    }
                    written++;
                    firstWrite.countDown();
                    Thread.sleep(2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                writerError.set(t);
            }
        });
        writer.setDaemon(true);
        writerBase.set(Instant.now());
        writer.start();

        try {
            // Concurrency facts, proven before the verdict:
            assertThat(firstWrite.await(10, TimeUnit.SECONDS))
                .as("the concurrent writer must have committed at least one row").isTrue();
            assertThat(writerError.get()).as("concurrent writer must not have failed").isNull();
            assertThat(claimerError.get()).as("concurrent claimer must not have failed").isNull();

            // The own rows enter the TEST transaction: from here on they are uncommitted — no other
            // transaction can see, lock, or claim them until the test ends.
            Instant now = Instant.now();
            PgItIsolation.insertCatchTimerJob(jdbc, ownFuture, UUID.randomUUID(), now.plusSeconds(3600));
            PgItIsolation.insertCatchTimerJob(jdbc, ownDue, UUID.randomUUID(), now.minusSeconds(10));
            inserted.countDown();

            // The claimer has now driven at least two full production cycles AROUND the own rows
            // (select + CAS each). With the protection in place its CAS could not win — the row was
            // uncommitted, so the claim was structurally impossible. Pinned as a fact, not assumed:
            // mutation M-B (the claimer never cycles) never releases this latch, and mutation M-A
            // (the protection removed) turns the next assertion red because the CAS then wins on the
            // committed row.
            assertThat(claimerRacedAroundVerdict.await(10, TimeUnit.SECONDS))
                .as("the concurrent claimer must have driven the production select+claim around the own rows")
                .isTrue();
            assertThat(ownDueClaimedByClaimer)
                .as("the claimer's production CAS could not win: the row is uncommitted in this transaction")
                .isFalse();

            // Stop the writer before the verdict (round 1: the cap is asserted, the join verified).
            writerRunning.set(false);
            writer.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(writer.isAlive())
                .as("writer thread must stop before the claim query runs").isFalse();
            assertThat(writtenByWriter.size())
                .as("writer must respect the OPS29 cap")
                .isLessThanOrEqualTo(ops29WriterCap);

            // THE VERDICT: the production claim in the SAME transaction as the inserts.
            List<UUID> claimedByEngine = timerJobRepository.findDueLocked(now, OPS29_CLAIM_WINDOW).stream()
                .map(TimerJobEntity::getId)
                .toList();
            List<UUID> claimedByMe = PgItIsolation.ownRowsAmong(List.of(ownFuture, ownDue), claimedByEngine);
            assertThat(claimedByMe).as("only this test's own due row is a claim candidate")
                .containsExactly(ownDue);
            assertThat(claimedByEngine)
                .as("a foreign due row was in the claim window and was excluded by scoping, not by absence")
                .contains(foreignLeftover);
            assertThat(claimedByEngine)
                .as("the own future-due row is not a claim candidate")
                .doesNotContain(ownFuture);
            assertThat(PgItIsolation.allDueTimerIds(jdbc))
                .as("the shared due set demonstrably contains a row this test did not own")
                .contains(foreignLeftover);
        } finally {
            inserted.countDown();
            claimerRunning.set(false);
            claimer.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(claimer.isAlive())
                .as("claimer thread must stop before the test ends").isFalse();
            writerRunning.set(false);
            writer.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(writer.isAlive())
                .as("writer thread must stop before the cleanup takes its snapshot").isFalse();
        }
    }

    /**
     * Criterion 2: the deployment's own timer-start job, and nothing else.
     *
     * <p>Seeds the CI failure shape — a timer-start row for the same process key that is already due
     * (created 10 minutes ago, scheduled 5 minutes after that: exactly what a leftover from an earlier
     * deployment looks like) — and pins both halves: the old wall-clock predicate cannot see such a
     * row ({@code due_at > NOW()} counts 0, the {@code expected: 1 but was: 0} of pipeline 175956), and
     * the scoped read used by {@code DeploymentAtomicityPgIT} still resolves to the deployment's own row.
     */
    @Test
    void criterion2_staleLeftoverJob_isNotMistakenForThisDeploymentsOwnJob() {
        Instant staleCreatedAt = Instant.now().minusSeconds(600);
        UUID staleDefinitionId = UUID.randomUUID();
        UUID staleJobId = UUID.randomUUID();
        // fired = true ON PURPOSE. The row must LOOK stale to the predicate under test (due_at far in the
        // past) yet be UNCLAIMABLE by a live TimerScheduler of a neighbouring, fully-unparked context:
        // processBatch() selects `fired = false AND due_at <= now()`. Leaving fired=false here would arm a
        // due timer start with a random process_definition_id, and the poller would claim it and fail on
        // fk_process_instances__process_definition_id — the very FK noise §9 escalates, reproduced by this
        // branch's own test (reviewer round 6).
        jdbc.update("INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at,"
                + " fired, created_at) VALUES (?, 'qw13-stale-key', ?, 'staleStart', ?, true, ?)",
            staleJobId, staleDefinitionId, Timestamp.from(staleCreatedAt.plusSeconds(300)),
            Timestamp.from(staleCreatedAt));

        try {
            // The old predicate, on the stale row: already due, so "due_at > NOW()" sees nothing.
            Integer visibleToOldPredicate = jdbc.queryForObject(
                "SELECT count(*) FROM timer_start_jobs WHERE process_definition_id = ? AND due_at > NOW()",
                Integer.class, staleDefinitionId);
            assertThat(visibleToOldPredicate).as("a stale row is invisible to the wall-clock predicate").isZero();

            // The deployment's own definition id: the scoped read is what the test now uses, and it
            // must not be satisfied by anybody else's row.
            UUID ownDefinitionId = UUID.randomUUID();
            Instant deployedAt = Instant.now().minusSeconds(5);
            UUID ownJobId = UUID.randomUUID();
            jdbc.update("INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at,"
                    + " fired, created_at) VALUES (?, 'qw13-stale-key', ?, 'ownStart', ?, false, ?)",
                ownJobId, ownDefinitionId, Timestamp.from(deployedAt.plusSeconds(300)),
                Timestamp.from(deployedAt));

            List<Instant> ownDueAts = jdbc.query(
                "SELECT due_at FROM timer_start_jobs WHERE process_key = ? AND process_definition_id = ?",
                (rs, i) -> rs.getTimestamp("due_at").toInstant(), "qw13-stale-key", ownDefinitionId);

            assertThat(ownDueAts).as("only the row of this definition may answer for it").hasSize(1);
            assertThat(ownDueAts.get(0)).isCloseTo(deployedAt.plusSeconds(300), within(1, ChronoUnit.MILLIS));

            // Different definition ids, different answers — no wall-clock, no whole-table count involved.
            assertThat(ownDueAts.get(0)).isAfter(staleCreatedAt.plusSeconds(300).plusSeconds(60));
        } finally {
            // criterion2 is NOT @Transactional (it inserts a deliberately stale row), so plain JDBC is
            // already its own committed transaction here — no extra wrapper needed.
            jdbc.update("DELETE FROM timer_start_jobs WHERE process_key = ?", "qw13-stale-key");
        }
    }
}
