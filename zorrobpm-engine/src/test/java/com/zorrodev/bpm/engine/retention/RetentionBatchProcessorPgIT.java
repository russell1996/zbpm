package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-7: Real PostgreSQL integration test for retention cascade delete.
 * Tagged @Tag("pg") via PostgresIT — excluded from default CI.
 *
 * Run locally:
 * <pre>
 * docker compose up -d postgres
 * mvn test -pl zorrobpm-engine -Dgroups=pg -Dtest=RetentionBatchProcessorPgIT
 * docker compose down postgres
 * </pre>
 */
public class RetentionBatchProcessorPgIT extends PostgresIT {

    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired VariableRepository variableRepository;
    @Autowired TimerJobRepository timerJobRepository;
    @Autowired MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired RetentionBatchProcessor batchProcessor;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE process_instances, activities, tokens, variables, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways RESTART IDENTITY CASCADE");
    }

    // ==================== Criterion #1: terminal instance cascaded ====================

    @Test
    void terminalInstance_oldEnough_cascadesAllChildren() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();

        // Insert process instance (completed, old enough)
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(100), Instant.now().minusSeconds(50));

        // Insert activity
        jdbcTemplate.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'startEvent', ?, ?, 'START_EVENT', 'COMPLETED', ?)",
            actId, piId, Instant.now().minusSeconds(90), Instant.now().minusSeconds(80), UUID.randomUUID());

        // Insert variable
        UUID varId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO variables (id, process_instance_id, scope_id, name, type, value) " +
            "VALUES (?, ?, ?, 'testVar', 'STRING', 'hello')",
            varId, piId, actId);

        // Insert timer job
        UUID timerId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) " +
            "VALUES (?, ?, ?, false, ?)",
            timerId, actId, Instant.now().minusSeconds(10), Instant.now().minusSeconds(90));

        // Run retention with TTL=1 day (cutoff = now - 86400s)
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).contains(piId);

        int deleted = batchProcessor.deleteInstances(eligible);
        assertThat(deleted).isGreaterThan(0);

        // Verify cascade: all child rows gone
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE activity_id = ?", Integer.class, actId)).isEqualTo(0);
    }

    // ==================== Criterion #2: active instance NOT deleted ====================

    @Test
    void activeInstance_notDeleted() {
        UUID piId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(100));

        // Active activity (completed_at IS NULL)
        jdbcTemplate.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'svc1', ?, NULL, 'SERVICE_TASK', 'CREATED', ?)",
            UUID.randomUUID(), piId, Instant.now().minusSeconds(90), UUID.randomUUID());

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(1);
    }

    // ==================== Criterion #3: in-flight not deleted ====================

    @Test
    void inFlightUserTask_notDeleted() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(100), Instant.now().minusSeconds(50));
        jdbcTemplate.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'usr1', ?, ?, 'USER_TASK', 'COMPLETED', ?)",
            actId, piId, Instant.now().minusSeconds(90), Instant.now().minusSeconds(80), UUID.randomUUID());
        // Active user task (completed_at IS NULL)
        jdbcTemplate.update(
            "INSERT INTO user_tasks (id, activity_id, process_instance_id, created_at, completed_at) " +
            "VALUES (?, ?, ?, ?, NULL)",
            UUID.randomUUID(), actId, piId, Instant.now().minusSeconds(80));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }

    @Test
    void inFlightServiceTask_notDeleted() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(100), Instant.now().minusSeconds(50));
        jdbcTemplate.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'svc1', ?, ?, 'SERVICE_TASK', 'COMPLETED', ?)",
            actId, piId, Instant.now().minusSeconds(90), Instant.now().minusSeconds(80), UUID.randomUUID());
        // Active service task (completed_at IS NULL)
        jdbcTemplate.update(
            "INSERT INTO service_tasks (id, activity_id, process_instance_id, created_at, completed_at, retries) " +
            "VALUES (?, ?, ?, ?, NULL, 3)",
            UUID.randomUUID(), actId, piId, Instant.now().minusSeconds(80));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }

    // ==================== Criterion #4: ttl=0 does nothing ====================

    @Test
    void ttlZero_doesNothing() {
        UUID piId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(100), Instant.now().minusSeconds(50));

        // With cutoff = now (no instances older than 0 seconds)
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).isEmpty();
    }

    // ==================== Criterion #5: not old enough ====================

    @Test
    void notOldEnough_notDeleted() {
        UUID piId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, UUID.randomUUID(), Instant.now().minusSeconds(10), Instant.now().minusSeconds(5));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }
}
