package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-8 (критерий 1): retention-запрос выборки обязан идти по индексу
 * {@code idx_process_instances_completed_at} (changeset 20260809-071, WO-PERF-2),
 * а не Seq Scan — иначе меньшие чанки не дают выигрыша на большой таблице.
 *
 * <p>Проверяются дословный SQL прод-метода
 * {@link RetentionBatchProcessor#findEligibleInstances(Instant, int)} (та же форма
 * предикатов и ORDER BY, cutoff/limit инлайнятся как литералы для EXPLAIN) и сам
 * артефакт в {@code pg_indexes} — т.е. доказывается существующий индекс, а не копия
 * его DDL (G-N). Прецедент — {@code FkIndexesPgIT} (seed + ANALYZE + план).
 */
public class RetentionExplainPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE process_instances, activities, tokens, variables, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways, process_definitions RESTART IDENTITY CASCADE");
    }

    @Test
    void completedAtIndexExists_createdByChangeset071() {
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes " +
            "WHERE indexname = 'idx_process_instances_completed_at'", Integer.class))
            .as("idx_process_instances_completed_at from changeset 20260809-071 must exist")
            .isEqualTo(1);
    }

    @Test
    void eligibleInstancesQuery_usesCompletedAtIndex_noSeqScan() {
        // Здоровая система: большинство строк — бегущие (completed_at IS NULL, вне
        // partial-индекса), меньшинство — старые завершённые (в индексе).
        UUID pdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'perf8-explain', 1, 'PERF-8 Explain', ?, ?)",
            pdId, UUID.randomUUID().toString(), ago(10_000));
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            batch.add(new Object[]{UUID.randomUUID(), pdId, ago(9_000), null, false});
        }
        for (int i = 0; i < 500; i++) {
            batch.add(new Object[]{UUID.randomUUID(), pdId, ago(9_000), ago(5_000), false});
        }
        jdbc.batchUpdate(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, ?)", batch);
        jdbc.execute("ANALYZE process_instances");

        // Дословная форма прод-запроса (cutoff/limit — литералы для EXPLAIN).
        String cutoff = ago(86_400).toString();
        String sql = "SELECT pi.id FROM process_instances pi " +
            "WHERE (pi.completed_at IS NOT NULL OR pi.cancelled = true) " +
            "AND pi.completed_at < TIMESTAMP '" + cutoff + "' " +
            "AND NOT EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id = pi.id AND a.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM user_tasks ut WHERE ut.process_instance_id = pi.id AND ut.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM service_tasks st WHERE st.process_instance_id = pi.id AND st.completed_at IS NULL) " +
            "ORDER BY pi.completed_at ASC LIMIT 25 FOR UPDATE OF pi SKIP LOCKED";
        List<String> planRows = jdbc.queryForList("EXPLAIN " + sql, String.class);
        String plan = String.join("\n", planRows);

        assertThat(plan)
            .as("retention select must use idx_process_instances_completed_at, plan was:\n" + plan)
            .contains("Index Scan using idx_process_instances_completed_at");
        // NOTE: Seq Scans on the NOT EXISTS side tables (activities/user_tasks/
        // service_tasks) are expected here — they are empty in this fixture, and the
        // criterion is the access path of the driving process_instances table.
        assertThat(plan)
            .as("retention select must not Seq Scan process_instances, plan was:\n" + plan)
            .doesNotContain("Seq Scan on process_instances");
    }
}
