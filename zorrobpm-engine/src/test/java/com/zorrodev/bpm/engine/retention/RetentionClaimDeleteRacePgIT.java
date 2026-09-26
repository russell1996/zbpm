package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-49 (остаток §5.2 аудита, строка 339): SELECT ... FOR UPDATE SKIP LOCKED выполнялся
 * в отдельном transactional-методе и завершался (release locks) ДО deleteInstances из
 * не-транзакционного job — две реплики могли выбрать те же ID после release locks.
 *
 * <p>Фикс: {@link RetentionBatchProcessor#claimAndDeleteOneInstance} держит claim (SELECT ...
 * LIMIT 1 FOR UPDATE SKIP LOCKED) и все DELETEs в ОДНОЙ транзакции. Две реальные PG-транзакции
 * (два потока, каждый в своих транзакциях через Spring-бин, V6) claim'ят из одного пула
 * eligible-инстансов: ни один инстанс не должен быть заclaimлен обоими, все должны уйти.
 */
public class RetentionClaimDeleteRacePgIT extends PostgresIT {

    private static final String PD_CODE = "rel49-race-test";

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void setUp() {
        // Residue wipe (children before parents) + our own seed code, same shape as FeelTimerPgIT.
        jdbc.execute("DELETE FROM timer_jobs");
        // Timer-start jobs fire from a background scheduler thread in the same context:
        // a definition wiped below must not leave an orphaned start job that fires mid-test
        // and FK-fails on insert (observed live: timer-batch-1 → fk_process_instances__process_definition_id).
        jdbc.execute("DELETE FROM timer_start_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'test-%' OR code = '" + PD_CODE + "'");
    }

    @Test
    void twoReplicas_claimDisjointInstances_noDuplicateWork() throws Exception {
        UUID pdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, '" + PD_CODE + "', 1, 'Rel49 Race Test', ?, ?)",
            pdId, UUID.randomUUID().toString(), ago(10_000));
        // history_time_to_live_days stays NULL → fallbackDays branch; terminal instances
        // completed well past the 90-day fallback cutoff, no activities/tasks → all eligible.
        int rows = 8;
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            UUID piId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
                "VALUES (?, ?, ?, ?, false)",
                piId, pdId, ago(92L * 86400), ago(91L * 86400));
            seeded.add(piId);
        }

        // Two real replicas: each loops claim+delete (own transactions via the Spring bean)
        // until the pool is drained. Barrier start so both hit the pool at once.
        Instant now = Instant.now();
        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<UUID> claimedA = new ArrayList<>();
        List<UUID> claimedB = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> drainThroughGate(gate, now, claimedA, failure));
            Future<?> f2 = pool.submit(() -> drainThroughGate(gate, now, claimedB, failure));
            f1.get(60, TimeUnit.SECONDS);
            f2.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).as("no replica thread may fail").isNull();

        // Discriminator (P-67): the sets must PARTITION the pool — union complete, intersection
        // empty. Without the single-transaction claim (locks released before delete) both
        // replicas select the same IDs and the intersection is non-empty.
        Set<UUID> union = new HashSet<>(claimedA);
        union.addAll(claimedB);
        assertThat(union).as("both replicas together must drain the whole pool").isEqualTo(seeded);
        Set<UUID> intersection = new HashSet<>(claimedA);
        intersection.retainAll(claimedB);
        assertThat(intersection)
            .as("no instance may be claimed by both replicas (duplicate work)")
            .isEmpty();
        // Both replicas did real work (the race actually ran on both sides, not sequentially
        // drained by one thread while the other idled).
        assertThat(claimedA).as("replica A must claim at least one instance").isNotEmpty();
        assertThat(claimedB).as("replica B must claim at least one instance").isNotEmpty();

        // Nothing left behind.
        Integer left = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id IN ('"
            + String.join("','", seeded.stream().map(UUID::toString).toList()) + "')",
            Integer.class);
        assertThat(left).as("all seeded instances must be deleted").isZero();
    }

    private void drainThroughGate(CyclicBarrier gate, Instant now, List<UUID> claimed,
            AtomicReference<Throwable> failure) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            while (true) {
                Optional<RetentionBatchProcessor.ClaimedDelete> done =
                    batchProcessor.claimAndDeleteOneInstance(now, 90);
                if (done.isEmpty()) {
                    return;
                }
                assertThat(done.get().rowsDeleted())
                    .as("a claimed instance must delete at least its own row")
                    .isPositive();
                claimed.add(done.get().instanceId());
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
