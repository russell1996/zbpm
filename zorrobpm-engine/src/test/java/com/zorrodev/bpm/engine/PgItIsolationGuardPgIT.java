package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WO-QW-13: the invariant that keeps {@code test:pg} order-independent, proven instead of assumed.
 *
 * <p>Both assertions below are the reason the two red tests of pipelines 175956 / 175964 are green
 * now: a PG test class shares one database with every other class AND with the live
 * {@code TimerScheduler} of every cached Spring context, so "the timer table is in the state my test
 * needs" is never true. What must be true is narrower and is what is asserted here:
 *
 * <ol>
 *   <li>a claim query scoped to the ids a test owns returns exactly those of the test's own rows,
 *       while the scheduler-wide due set demonstrably contains foreign rows (so the old
 *       whole-table {@code isEmpty()} assertion could not hold);</li>
 *   <li>a timer-start row that is NOT the row this deployment created — the stale leftover shape that
 *       made {@code DeploymentAtomicityPgIT:146} report {@code expected: 1 but was: 0} — is never
 *       mistaken for the deployment's own job, and the deployment's own job is pinned to the instant
 *       the test deployed plus the BPMN's duration instead of to the database wall clock.</li>
 * </ol>
 *
 * <p>Self-cleaning: every row this class creates is removed again in a {@code finally}, so the guard
 * does not push the problem it documents onto the classes that run after it.
 */
public class PgItIsolationGuardPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired TimerJobRepository timerJobRepository;

    /** The poller is parked so the guard measures the tests' own doing, not a racing scheduler. */
    @DynamicPropertySource
    static void parkBackgroundTimerPollers(DynamicPropertyRegistry registry) {
        PgItIsolation.parkBackgroundTimerPollers(registry);
    }

    /**
     * Criterion 1: the claim set of THIS test is immune to (a) a leftover due row and (b) a concurrent
     * writer — the live poller of another cached context, reproduced here by a thread that inserts due
     * rows while the test reads.
     *
     * <p>The candidate set is produced by the PRODUCTION query
     * {@link TimerJobRepository#findDueLocked} (G-N: a test must judge the real query, not a copy of its
     * WHERE clause) and then narrowed to this test's own ids by
     * {@link PgItIsolation#ownRowsAmong}. So breaking the production predicate turns this test red, while
     * rows this test never created cannot.
     */
    @Test
    @Transactional
    void criterion1_claimSetScopedByOwnIds_ignoresForeignAndConcurrentlyWrittenDueRows() throws Exception {
        UUID foreignLeftover = PgItIsolation.insertCatchTimerJob(jdbc, UUID.randomUUID(),
            Instant.now().minusSeconds(30));
        AtomicBoolean writerRunning = new AtomicBoolean(true);
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        List<UUID> writtenByWriter = new ArrayList<>();
        // WO-OPS-11 pattern: coordinate by FACT, never by a fixed sleep — the writer signals after its
        // first committed insert, so the assertion below can never lose a race with thread start-up.
        CountDownLatch firstWrite = new CountDownLatch(1);
        UUID ownFuture = UUID.randomUUID();
        UUID ownDue = UUID.randomUUID();

        // A second writer, exactly like TimerScheduler in another context: it inserts DUE rows while
        // the test reads. Old code asserted on the whole table, so any of these rows decided its verdict.
        Thread writer = new Thread(() -> {
            try {
                while (writerRunning.get()) {
                    writtenByWriter.add(PgItIsolation.insertCatchTimerJob(jdbc, UUID.randomUUID(),
                        Instant.now().minusSeconds(5)));
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
        writer.start();

        try {
            // The foreign rows must ALREADY be committed when the claim query runs — otherwise the
            // claim set would be trivially clean and the guard would prove nothing.
            assertThat(firstWrite.await(10, TimeUnit.SECONDS))
                .as("the concurrent writer must have committed at least one row").isTrue();
            assertThat(writerError.get()).as("concurrent writer must not have failed").isNull();
            assertThat(writtenByWriter).as("the writer really did write concurrently").isNotEmpty();

            Instant now = Instant.now();
            PgItIsolation.insertCatchTimerJob(jdbc, ownFuture, UUID.randomUUID(), now.plusSeconds(3600));
            PgItIsolation.insertCatchTimerJob(jdbc, ownDue, UUID.randomUUID(), now.minusSeconds(10));

            List<UUID> claimedByEngine = timerJobRepository.findDueLocked(now, 100).stream()
                .map(TimerJobEntity::getId)
                .toList();
            List<UUID> claimedByMe = PgItIsolation.ownRowsAmong(List.of(ownFuture, ownDue), claimedByEngine);
            assertThat(claimedByMe).as("only this test's own due row is a claim candidate")
                .containsExactly(ownDue);

            // The premise of the old assertion — "the table has no due rows I did not create" — is
            // provably false in a shared database. This is why `isEmpty()` could not be the criterion.
            // Snapshot first: the writer thread keeps appending while we read.
            List<UUID> writtenSnapshot = List.copyOf(writtenByWriter);
            assertThat(PgItIsolation.allDueTimerIds(jdbc))
                .contains(foreignLeftover)
                .containsAll(writtenSnapshot);
        } finally {
            writerRunning.set(false);
            writer.join(5000);
            List<UUID> toDelete = new ArrayList<>();
            toDelete.add(foreignLeftover);
            toDelete.add(ownFuture);
            toDelete.add(ownDue);
            toDelete.addAll(writtenByWriter);
            PgItIsolation.deleteTimerJobs(jdbc, toDelete);
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
        jdbc.update("INSERT INTO timer_start_jobs (id, process_key, process_definition_id, element_id, due_at,"
                + " fired, created_at) VALUES (?, 'qw13-stale-key', ?, 'staleStart', ?, false, ?)",
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
            jdbc.update("DELETE FROM timer_start_jobs WHERE process_key = ?", "qw13-stale-key");
        }
    }
}