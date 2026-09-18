package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.repository.OutboxRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-8 (G-N): the migration is proved by RUNNING it, not by copying its SQL.
 * Liquibase applies changeset {@code 20260918-113} at context startup; this test
 * inserts a row WITH a traceparent and reads it back through the REAL column —
 * delete the changeset and the insert fails (no such column), which is exactly
 * the regression this guards.
 *
 * <p>H2-only (column add + comment; PG parity rides the shared
 * {@code ci/run-pg-tests.sh} suite via the {@code @Tag("pg")} twin below —
 * same table, same additive-expand shape).
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxTraceParentMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void traceParentColumn_existsAndRoundTrips() {
        assertThat(outboxRepository.count()).isGreaterThanOrEqualTo(0);

        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_NAME = 'OUTBOX' AND COLUMN_NAME = 'TRACE_PARENT'");
        assertThat(cols).as("changeset 20260918-113 must have added outbox.trace_parent").hasSize(1);

        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        jdbc.update("INSERT INTO outbox (id, kind, payload, created_at, published, attempts, status, trace_parent) "
            + "VALUES (RANDOM_UUID(), 'SERVICE_TASK', '{\"a\":1}', CURRENT_TIMESTAMP, false, 0, 'PENDING', ?)",
            traceParent);
        String readBack = jdbc.queryForObject(
            "SELECT trace_parent FROM outbox WHERE trace_parent = ?", String.class, traceParent);
        assertThat(readBack).isEqualTo(traceParent);
    }

    @Test
    void traceParentColumn_nullable_legacyRowsUnaffected() {
        jdbc.update("INSERT INTO outbox (id, kind, payload, created_at, published, attempts, status) "
            + "VALUES (RANDOM_UUID(), 'EMAIL', '{\"b\":2}', CURRENT_TIMESTAMP, false, 0, 'PENDING')");
        Long nulls = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE trace_parent IS NULL", Long.class);
        assertThat(nulls).isGreaterThanOrEqualTo(1);
    }
}
