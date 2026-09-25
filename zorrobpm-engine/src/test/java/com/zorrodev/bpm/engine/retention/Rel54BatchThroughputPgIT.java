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
 * WO-REL-54 (NEW-12): claim+delete пачкой в одной транзакции — тот же
 * SKIP LOCKED-механизм REL-49, шире окно.
 *
 * <ul>
 *   <li>Criterion 1: число SQL-statement'ов за прогон растёт на ПАЧКУ, не на
 *       инстанс (счётчик statement'ов через обёртку datasource'а — см.
 *       {@link CountingPgIT}; числа ДО/ПОСЛЕ — в отчёте).</li>
 *   <li>Criterion 2: та же двухпоточная race-проверка REL-49 на пачечной
 *       версии — 0 дублей, 0 потерь.</li>
 *   <li>Criterion 3: см. {@code Rel54PassBudgetTest} (unit, дедлайн между
 *       пачками); здесь — сквозной прогон пачками до пустого claim'а.</li>
 * </ul>
 */
class Rel54BatchThroughputPgIT extends PostgresIT {

    private static final String PD_CODE = "rel54-batch-test";

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM timer_start_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM variable_history");
        jdbc.execute("DELETE FROM element_listener_phase");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'test-%' OR code = '" + PD_CODE + "'");
    }

    private Set<UUID> seed(int rows) {
        UUID pdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, '" + PD_CODE + "', 1, 'Rel54 Batch Test', ?, ?)",
            pdId, UUID.randomUUID().toString(), ago(10_000));
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            UUID piId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
                "VALUES (?, ?, ?, ?, false)",
                piId, pdId, ago(92L * 86400), ago(91L * 86400));
            seeded.add(piId);
        }
        return seeded;
    }

    @Test
    void batchDrain_deletesAll_noLeftovers() {
        Set<UUID> seeded = seed(60);
        Instant now = Instant.now();
        List<Integer> ttls = batchProcessor.loadDistinctTtls();
        int deleted = 0;
        int batches = 0;
        while (true) {
            RetentionBatchProcessor.ClaimedBatch done =
                batchProcessor.claimAndDeleteBatch(now, 90, 25, ttls);
            if (done.instanceIds().isEmpty()) break;
            batches++;
            deleted += done.instanceIds().size();
            assertThat(done.rowsDeleted())
                .as("a claimed batch must delete at least its own instance rows")
                .isGreaterThanOrEqualTo(done.instanceIds().size());
        }
        assertThat(deleted).as("all seeded instances drained in batches").isEqualTo(60);
        assertThat(batches).as("60 instances at batch 25 → 3 batches").isEqualTo(3);
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM process_instances", Integer.class);
        assertThat(left).isZero();
    }

    @Test
    void twoReplicas_batchClaimDisjoint_noDuplicatesNoLoss() throws Exception {
        Set<UUID> seeded = seed(40);
        Instant now = Instant.now();
        List<Integer> ttls = batchProcessor.loadDistinctTtls();

        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<UUID> claimedA = new ArrayList<>();
        List<UUID> claimedB = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> drainBatches(gate, now, ttls, claimedA, failure));
            Future<?> f2 = pool.submit(() -> drainBatches(gate, now, ttls, claimedB, failure));
            f1.get(120, TimeUnit.SECONDS);
            f2.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).as("no replica thread may fail").isNull();

        Set<UUID> union = new HashSet<>(claimedA);
        union.addAll(claimedB);
        assertThat(union).as("both replicas together must drain the whole pool").isEqualTo(seeded);
        Set<UUID> intersection = new HashSet<>(claimedA);
        intersection.retainAll(claimedB);
        assertThat(intersection)
            .as("no instance may be claimed by both replicas (duplicate work)")
            .isEmpty();
        assertThat(claimedA).as("replica A must claim at least one batch").isNotEmpty();
        assertThat(claimedB).as("replica B must claim at least one batch").isNotEmpty();

        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM process_instances", Integer.class);
        assertThat(left).as("all seeded instances must be deleted").isZero();
    }

    private void drainBatches(CyclicBarrier gate, Instant now, List<Integer> ttls,
            List<UUID> claimed, AtomicReference<Throwable> failure) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            while (true) {
                RetentionBatchProcessor.ClaimedBatch done =
                    batchProcessor.claimAndDeleteBatch(now, 90, 10, ttls);
                if (done.instanceIds().isEmpty()) {
                    return;
                }
                assertThat(done.rowsDeleted()).isPositive();
                claimed.addAll(done.instanceIds());
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
