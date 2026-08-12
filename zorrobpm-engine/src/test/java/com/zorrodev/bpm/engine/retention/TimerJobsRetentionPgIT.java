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

    // ==================== POF (G-K): RED before backfill, GREEN after ====================

    /**
     * POF (G-K, G-N): demonstrates the pre-fix bug.
     * Before the fix, boundary timer jobs were created with NULL process_instance_id.
     * Retention's DELETE uses WHERE process_instance_id IN (:ids), and NULL NOT IN (...)
     * never matches — so fired boundary jobs accumulate forever.
     *
     * RED: insert a fired boundary timer with NULL process_instance_id → retention does NOT delete it.
     * GREEN: backfill migration sets process_instance_id → retention deletes it.
     */
    @Test
    void pof_boundaryTimerWithNullProcessInstanceId_survivesRetention_beforeBackfill() {
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

        // Pre-fix boundary timer job: NULL process_instance_id (the bug)
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, NULL, 'boundary1', ?, true, ?)",
            timerId, actId, ago(10), ago(90));

        // --- RED: retention does NOT delete it (NULL NOT IN (...) never matches) ---
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);
        batchProcessor.deleteInstances(eligible);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId))
            .isEqualTo(1); // <-- RED: timer survives despite completed process

        // --- Apply backfill migration (simulates what Liquibase does) ---
        jdbc.update(
            "UPDATE timer_jobs SET process_instance_id = (" +
            "  SELECT pi.id FROM process_instances pi" +
            "  JOIN activities a ON a.process_instance_id = pi.id" +
            "  WHERE a.id = timer_jobs.activity_id" +
            "  LIMIT 1" +
            ") WHERE process_instance_id IS NULL AND boundary_element_id IS NOT NULL");

        // Verify backfill worked
        UUID backfilledPiId = jdbc.queryForObject(
            "SELECT process_instance_id FROM timer_jobs WHERE id = ?", UUID.class, timerId);
        assertThat(backfilledPiId).isEqualTo(piId);

        // --- GREEN: retention now deletes it ---
        eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);
        batchProcessor.deleteInstances(eligible);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId))
            .isEqualTo(0); // <-- GREEN: timer deleted after backfill
    }

    // ==================== Historical NULL process_instance_id backfill ====================

    /**
     * Verifies the backfill migration works: boundary jobs with NULL process_instance_id
     * get their process_instance_id populated from the activities table.
     */
    @Test
    void historicalBoundaryJobs_backfilledProcessInstanceId() {
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

        // Historical boundary timer job with NULL process_instance_id (pre-fix behavior)
        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, boundary_element_id, due_at, fired, created_at) " +
            "VALUES (?, ?, NULL, 'boundary1', ?, true, ?)",
            timerId, actId, ago(10), ago(90));

        // Simulate backfill migration
        jdbc.update(
            "UPDATE timer_jobs SET process_instance_id = (" +
            "  SELECT pi.id FROM process_instances pi" +
            "  JOIN activities a ON a.process_instance_id = pi.id" +
            "  WHERE a.id = timer_jobs.activity_id" +
            "  LIMIT 1" +
            ") WHERE process_instance_id IS NULL AND boundary_element_id IS NOT NULL");

        // Verify backfill
        UUID backfilledPiId = jdbc.queryForObject(
            "SELECT process_instance_id FROM timer_jobs WHERE id = ?", UUID.class, timerId);
        assertThat(backfilledPiId).isEqualTo(piId);

        // Now retention should clean it up
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);

        batchProcessor.deleteInstances(eligible);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE id = ?", Integer.class, timerId)).isEqualTo(0);
    }
}