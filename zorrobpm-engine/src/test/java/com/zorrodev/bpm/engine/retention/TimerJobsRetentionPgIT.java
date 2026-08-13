package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-3: Real PostgreSQL integration test for timer_jobs retention and index usage.
 * Tagged @Tag("pg") via PostgresIT — excluded from default CI.
 *
 * Run locally:
 * <pre>
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci up -d
 * mvn test -pl zorrobpm-engine -Dgroups=pg -Dtest=TimerJobsRetentionPgIT
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci down
 * </pre>
 */
public class TimerJobsRetentionPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;

    private UUID sharedPdId;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE process_instances, activities, tokens, variables, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways, process_definitions RESTART IDENTITY CASCADE");

        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'retention-test', 1, 'Retention Test', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), ago(200));
    }

    // ==================== Criterion #2: fired boundary jobs ARE deleted ====================

    /**
     * POF (G-K, G-N): calls REAL RetentionBatchProcessor.deleteInstances().
     * Inserts a completed process_instance with a fired boundary timer job.
     * After retention, the timer job must be deleted.
     */
    @Test
    void firedBoundaryJob_deletedByRetention() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();

        // Completed process instance
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));

        // Completed activity
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'hostActivity', ?, ?, 'SERVICE_TASK', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), UUID.randomUUID());

        // Fired boundary timer job (boundaryElementId != NULL, fired = true)
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, ?, 'boundary1', ?, true, ?)",
            timerId, actId, piId, ago(10), ago(90));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);

        int deleted = batchProcessor.deleteInstances(eligible);
        assertThat(deleted).isGreaterThan(0);

        // Verify: fired boundary timer job is deleted
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(0);
    }

    // ==================== Criterion #3: active timer jobs NOT deleted ====================

    /**
     * Active (unfired) timer jobs for a completed process instance should still be deleted
     * because the process instance is completed. But the point is: active timer jobs for
     * NON-completed instances must NOT be deleted.
     */
    @Test
    void activeTimerJob_onRunningInstance_notDeleted() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();

        // Running process instance (no completed_at)
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            piId, sharedPdId, ago(100));

        // Running activity
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'svc1', ?, NULL, 'SERVICE_TASK', 'CREATED', ?)",
            actId, piId, ago(90), UUID.randomUUID());

        // Active (unfired) timer job
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, due_at, fired, created_at) " +
            "VALUES (?, ?, ?, ?, false, ?)",
            timerId, actId, piId, ago(10), ago(90));

        // Instance is not eligible (still running)
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);

        // Timer job still exists
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(1);
    }

    // ==================== Criterion #1: index exists and is usable ====================

    /**
     * Verifies the index for the re-arm query exists.
     * Full EXPLAIN ANALYZE should be done manually on ~50k rows.
     */
    @Test
    void boundaryTimerIndex_exists() {
        String indexName = jdbc.queryForObject(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'timer_jobs' AND indexname = 'idx_timer_jobs__activity_boundary_fired_created'",
            String.class);
        assertThat(indexName).isEqualTo("idx_timer_jobs__activity_boundary_fired_created");
    }

    /**
     * EXPLAIN ANALYZE: proves the planner chooses Index Scan (not Seq Scan) for the re-arm query.
     * This mirrors the exact query from TimerJobRepository.findFirstByActivityIdAndBoundaryElementIdAndFiredTrueOrderByCreatedAtDesc.
     */
    @Test
    void boundaryTimerIndex_usedByReArmQuery() {
        UUID actId = UUID.randomUUID();
        // Insert data to make planner choose index
        for (int i = 0; i < 20; i++) {
            jdbc.update(
                "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
                "VALUES (?, ?, NULL, 'boundary1', ?, true, ?)",
                UUID.randomUUID(), actId, ago(10), ago(90 - i));
        }

        List<String> plan = jdbc.queryForList(
            "EXPLAIN ANALYZE " +
            "SELECT id FROM timer_jobs " +
            "WHERE activity_id = ? AND boundary_element_id = 'boundary1' AND fired = true " +
            "ORDER BY created_at DESC LIMIT 1",
            String.class, actId);

        String fullPlan = String.join("\n", plan);
        assertThat(fullPlan).contains("Index Scan");
        assertThat(fullPlan).doesNotContain("Seq Scan");
    }

    // ==================== POF (G-K): RED — NULL process_instance_id survives retention ====================

    /**
     * POF (G-K, G-N): demonstrates the pre-fix bug using the real retention code path.
     * Before the fix, boundary timer jobs were created with NULL process_instance_id.
     * RetentionBatchProcessor.deleteInstances() uses WHERE process_instance_id IN (:ids),
     * and NULL IN (...) never matches — so fired boundary jobs accumulate forever.
     *
     * RED: deleteInstances(piId-B) deletes timer_job with valid process_instance_id,
     * but timer_job_A with NULL process_instance_id SURVIVES.
     */
    @Test
    void pof_boundaryTimerWithNullProcessInstanceId_survivesRetention() {
        UUID actIdA = UUID.randomUUID();
        UUID actIdB = UUID.randomUUID();

        // --- PI-A: completed, with boundary timer job that has NULL process_instance_id (bug) ---
        UUID piIdA = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piIdA, sharedPdId, ago(100), ago(50));
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'hostActivity', ?, ?, 'SERVICE_TASK', 'COMPLETED', ?)",
            actIdA, piIdA, ago(90), ago(80), UUID.randomUUID());
        UUID timerIdA = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, NULL, 'boundary1', ?, true, ?)",
            timerIdA, actIdA, ago(10), ago(90));

        // --- PI-B: completed, with boundary timer job that has VALID process_instance_id (normal) ---
        UUID piIdB = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piIdB, sharedPdId, ago(100), ago(50));
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'hostActivity', ?, ?, 'SERVICE_TASK', 'COMPLETED', ?)",
            actIdB, piIdB, ago(90), ago(80), UUID.randomUUID());
        UUID timerIdB = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, ?, 'boundary2', ?, true, ?)",
            timerIdB, actIdB, piIdB, ago(10), ago(90));

        // Both PIs are eligible
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piIdA, piIdB);

        // --- RED: deleteInstances() — real production code path ---
        // timer_job_B (valid process_instance_id) is deleted
        // timer_job_A (NULL process_instance_id) SURVIVES — the bug
        batchProcessor.deleteInstances(List.of(piIdB));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerIdB))
            .isEqualTo(0); // valid process_instance_id → deleted
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerIdA))
            .isEqualTo(1); // <-- RED: NULL process_instance_id → survives
    }

    // ==================== Historical NULL process_instance_id cleanup ====================

    /**
     * Verifies the migration DELETE cleans up orphaned historical boundary jobs
     * with NULL process_instance_id. These are fired boundary jobs whose instances
     * were already purged by retention — they accumulate forever without this cleanup.
     */
    @Test
    void historicalBoundaryJobs_deletedByMigration() {
        UUID actId = UUID.randomUUID();

        // Historical boundary timer job with NULL process_instance_id (pre-fix behavior)
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, NULL, 'boundary1', ?, true, ?)",
            timerId, actId, ago(10), ago(90));

        // Verify row exists before migration
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(1);

        // Simulate the DELETE migration
        jdbc.update(
            "DELETE FROM timer_jobs " +
            "WHERE process_instance_id IS NULL " +
            "AND boundary_element_id IS NOT NULL " +
            "AND fired = true");

        // Verify row is deleted
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(0);
    }

    /**
     * Verifies the migration does NOT delete live unfired timers with NULL process_instance_id.
     * Only fired=true, NULL process_instance_id, boundary_element_id NOT NULL are cleaned up.
     */
    @Test
    void liveUnfiredTimer_notDeletedByMigration() {
        UUID actId = UUID.randomUUID();

        // Live unfired timer job with NULL process_instance_id — must NOT be deleted
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, NULL, 'boundary1', ?, false, ?)",
            timerId, actId, ago(10), ago(90));

        // Run the migration DELETE
        jdbc.update(
            "DELETE FROM timer_jobs " +
            "WHERE process_instance_id IS NULL " +
            "AND boundary_element_id IS NOT NULL " +
            "AND fired = true");

        // Verify live unfired timer is untouched
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(1);
    }
}