package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-8 (G-N, PG half): changeset {@code 20260918-113} applied by Liquibase on a
 * REAL PostgreSQL — {@code COMMENT ON COLUMN} + insert/read-back through the real
 * {@code trace_parent} column. H2 accepts comment syntax the PG catalogue may reject
 * (and vice versa), so the H2 twin ({@link OutboxTraceParentMigrationIT}) is not
 * sufficient proof for the {@code dbms: postgresql} branch.
 *
 * <p>Run: {@code ci/run-pg-tests.sh} (or the {@code pg-it-run.md} command verbatim).
 */
@Tag("pg")
@SpringBootTest
@ActiveProfiles("test")
class OutboxTraceParentMigrationPgIT extends PostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void traceParentColumn_existsAndRoundTrips_onPostgres() {
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
            + "WHERE table_name = 'outbox' AND column_name = 'trace_parent'");
        assertThat(cols).as("changeset 20260918-113 must have added outbox.trace_parent").hasSize(1);

        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox (id, kind, payload, created_at, published, attempts, status, trace_parent) "
            + "VALUES (?, 'SERVICE_TASK', '{\"a\":1}', now(), false, 0, 'PENDING', ?)",
            id, traceParent);
        String readBack = jdbc.queryForObject(
            "SELECT trace_parent FROM outbox WHERE id = ?", String.class, id);
        assertThat(readBack).isEqualTo(traceParent);
    }
}
