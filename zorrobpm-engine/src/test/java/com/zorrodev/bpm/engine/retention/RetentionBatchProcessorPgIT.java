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
 * WO-REL-7: Real PostgreSQL integration test for retention cascade delete.
 * Tagged @Tag("pg") via PostgresIT — excluded from default CI.
 *
 * Run locally:
 * <pre>
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci up -d
 * mvn test -pl zorrobpm-engine -Dgroups=pg -Dtest=RetentionBatchProcessorPgIT
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci down
 * </pre>
 */
public class RetentionBatchProcessorPgIT extends PostgresIT {

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

        // Insert a valid process_definitions row (FK target for process_instances)
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'retention-test', 1, 'Retention Test', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), ago(200));
    }

    // ==================== Criterion #1: terminal instance cascaded ====================

    @Test
    void terminalInstance_oldEnough_cascadesAllChildren() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();

        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));

        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        UUID token1 = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", token1);
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'startEvent', ?, ?, 'START_EVENT', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), token1);

        UUID varId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
            "VALUES (?, ?, ?, 'testVar', 'STRING', 'hello')",
            varId, piId, actId);

        UUID timerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, process_instance_id, due_at, fired, created_at) " +
            "VALUES (?, ?, ?, ?, false, ?)",
            timerId, actId, piId, ago(10), ago(90));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);

        int deleted = batchProcessor.deleteInstances(eligible);
        assertThat(deleted).isGreaterThan(0);

        // Verify cascade: all child rows gone
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE activity_id = ?", Integer.class, actId)).isEqualTo(0);
    }

    // ==================== Criterion #2: active instance NOT deleted ====================

    @Test
    void activeInstance_notDeleted() {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            piId, sharedPdId, ago(100));

        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        UUID token2 = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", token2);
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'svc1', ?, NULL, 'SERVICE_TASK', 'CREATED', ?)",
            UUID.randomUUID(), piId, ago(90), token2);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(1);
    }

    // ==================== Criterion #3: in-flight not deleted ====================

    @Test
    void inFlightUserTask_notDeleted() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));
        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        UUID token3 = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", token3);
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'usr1', ?, ?, 'USER_TASK', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), token3);
        jdbc.update(
            "INSERT INTO user_tasks (id, process_instance_id, process_definition_id, bpmn_element_id, created_at, completed_at) " +
            "VALUES (?, ?, ?, 'userTask', ?, NULL)",
            UUID.randomUUID(), piId, sharedPdId, ago(80));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }

    @Test
    void inFlightServiceTask_notDeleted() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));
        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        UUID token4 = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", token4);
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'svc1', ?, ?, 'SERVICE_TASK', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), token4);
        jdbc.update(
            "INSERT INTO service_tasks (id, process_instance_id, process_definition_id, bpmn_element_id, created_at, completed_at, retries_remaining) " +
            "VALUES (?, ?, ?, 'svcTask', ?, NULL, 3)",
            UUID.randomUUID(), piId, sharedPdId, ago(80));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }

    // ==================== Criterion #4: ttl=0 does nothing ====================

    @Test
    void ttlZero_doesNothing() {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(100), 100);
        assertThat(eligible).isEmpty();
    }

    // ==================== Criterion #5: not old enough ====================

    @Test
    void notOldEnough_notDeleted() {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(10), ago(5));

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now().minusSeconds(86400), 100);
        assertThat(eligible).doesNotContain(piId);
    }

    // ==================== WO-REL-9: orphaned tokens are deleted ====================

    /**
     * POF (G-N): calls REAL RetentionBatchProcessor.deleteInstances().
     * Inserts a process_instance, activity with token reference, and the token itself.
     * After retention, token count must be 0.
     *
     * RED (before fix): activities deleted first → subquery returns empty → tokens survive.
     * GREEN (after fix): token IDs collected before activities delete → tokens removed.
     */
    @Test
    void tokensDeleted_afterRetention() {
        UUID piId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));

        // WO-OPS-12: fk_activities__token — токен раньше ссылающейся activity.
        jdbc.update(
            "INSERT INTO tokens (id) VALUES (?)",
            tokenId);

        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'startEvent', ?, ?, 'START_EVENT', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), tokenId);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);

        batchProcessor.deleteInstances(eligible);

        // POF: token must be deleted
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tokens WHERE id = ?", Integer.class, tokenId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(0);
    }
}
