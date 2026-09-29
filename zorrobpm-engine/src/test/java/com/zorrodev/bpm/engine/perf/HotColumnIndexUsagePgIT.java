package com.zorrodev.bpm.engine.perf;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-2 (D-02): proves the new indexes are actually chosen by the PostgreSQL planner on a
 * production-like row count (3000 rows/table) — not just that they exist. A CREATE INDEX that
 * the planner never picks (wrong column order, too small a table, stale stats) fixes nothing.
 *
 * Run locally:
 * <pre>
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci up -d
 * mvn test -pl zorrobpm-engine -Dgroups=pg -Dtest=HotColumnIndexUsagePgIT
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci down
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HotColumnIndexUsagePgIT extends PostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    private static final int ROWS = 3000;

    private UUID sampleUserId;
    private String sampleKeyHash;
    private UUID sampleProcessDefinitionId;

    @BeforeAll
    void seed() {
        jdbc.execute("TRUNCATE api_key_grant, api_key, process_member, process, " +
            "process_instances, process_definitions, ui_users RESTART IDENTITY CASCADE");

        jdbc.execute("""
            INSERT INTO ui_users (id, username, password_hash, role, active, created_at, updated_at)
            SELECT gen_random_uuid(), 'perfuser' || gs, 'hash', 'USER', true, now(), now()
            FROM generate_series(1, %d) gs
            """.formatted(ROWS));

        jdbc.execute("""
            INSERT INTO api_key (id, owner_user_id, key_hash, prefix, created_at)
            SELECT gen_random_uuid(), id, md5(id::text), 'pk_', now()
            FROM ui_users
            """);
        sampleKeyHash = jdbc.queryForObject("SELECT key_hash FROM api_key LIMIT 1", String.class);

        UUID processId = UUID.randomUUID();
        jdbc.update("INSERT INTO process (id, definition_key, name, created_at) VALUES (?, 'perf-process', 'Perf Process', now())", processId);
        jdbc.execute("""
            INSERT INTO process_member (process_id, user_id, role, added_at)
            SELECT '%s', id, 'MEMBER', now() FROM ui_users
            """.formatted(processId));
        sampleUserId = jdbc.queryForObject("SELECT user_id FROM process_member LIMIT 1", UUID.class);

        sampleProcessDefinitionId = UUID.randomUUID();
        jdbc.update("INSERT INTO process_definitions (id, code, version, name, sha256, created_at) VALUES (?, 'perf-def', 1, 'Perf Def', ?, now())",
            sampleProcessDefinitionId, UUID.randomUUID().toString());
        // completed_at spreads 2..ROWS days into the past so a retention cutoff far out in that
        // tail (see retentionCutoff()) is selective — mirrors production, where most completed
        // instances are recent (within the retention TTL) and only a small tail is purge-eligible.
        jdbc.execute("""
            INSERT INTO process_instances (id, process_definition_id, started_at, completed_at)
            SELECT gen_random_uuid(), '%s', now() - (gs || ' days')::interval,
                   CASE WHEN gs %% 2 = 0 THEN now() - (gs || ' days')::interval ELSE NULL END
            FROM generate_series(1, %d) gs
            """.formatted(sampleProcessDefinitionId, ROWS));

        jdbc.execute("ANALYZE ui_users, api_key, process_member, process_instances");
    }

    private String explain(String sql, Object... args) {
        List<String> lines = jdbc.query("EXPLAIN " + sql, (rs, rowNum) -> rs.getString(1), args);
        return String.join("\n", lines);
    }

    @Test
    void apiKeyLookupByKeyHashUsesIndex() {
        String plan = explain("SELECT * FROM api_key WHERE key_hash = ?", sampleKeyHash);
        assertThat(plan).as("plan:%n%s", plan).containsIgnoringCase("Index Scan").doesNotContainIgnoringCase("Seq Scan");
    }

    @Test
    void processMemberLookupByUserIdUsesIndex() {
        String plan = explain("SELECT * FROM process_member WHERE user_id = ?", sampleUserId);
        assertThat(plan).as("plan:%n%s", plan).containsIgnoringCase("Index Scan").doesNotContainIgnoringCase("Seq Scan");
    }

    @Test
    void processInstancesByDefinitionOrderedByStartedAtUsesIndex() {
        String plan = explain(
            "SELECT * FROM process_instances WHERE process_definition_id = ? ORDER BY started_at DESC LIMIT 20",
            sampleProcessDefinitionId);
        assertThat(plan).as("plan:%n%s", plan).containsIgnoringCase("Index Scan").doesNotContainIgnoringCase("Seq Scan");
    }

    @Test
    void processInstancesRetentionScanByCompletedAtUsesPartialIndex() {
        // selective cutoff, far out in the seeded tail (see seed()) — only a handful of the
        // ROWS/2 completed rows qualify, same shape as RetentionBatchProcessor's real query.
        String plan = explain("SELECT * FROM process_instances WHERE completed_at IS NOT NULL AND completed_at < now() - interval '" + (ROWS - 50) + " days'");
        assertThat(plan).as("plan:%n%s", plan)
            .containsIgnoringCase("idx_process_instances_completed_at")
            .doesNotContainIgnoringCase("Seq Scan");
    }
}
