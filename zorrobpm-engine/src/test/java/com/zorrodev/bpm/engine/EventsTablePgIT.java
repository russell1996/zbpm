package com.zorrodev.bpm.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-EVT-1: PG-IT for the events table migration.
 * Verifies the schema exists and has correct columns/indexes on real PostgreSQL.
 */
@Tag("pg")
class EventsTablePgIT extends PostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void eventsTableExists_withCorrectColumns() {
        // Verify the events table exists
        List<Map<String, Object>> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_name = 'events'");
        assertThat(tables).isNotEmpty();

        // Verify key columns exist
        List<Map<String, Object>> columns = jdbc.queryForList(
            "SELECT column_name, data_type, is_nullable " +
            "FROM information_schema.columns WHERE table_name = 'events' ORDER BY ordinal_position");

        assertThat(columns).isNotEmpty();

        // Check sequence column (BIGSERIAL = bigint with nextval)
        Map<String, Object> seqCol = columns.stream()
            .filter(c -> "sequence".equals(c.get("column_name")))
            .findFirst().orElseThrow();
        assertThat(seqCol.get("data_type").toString()).isEqualTo("bigint");
        assertThat(seqCol.get("is_nullable").toString()).isEqualTo("NO");

        // Check id column (UUID)
        Map<String, Object> idCol = columns.stream()
            .filter(c -> "id".equals(c.get("column_name")))
            .findFirst().orElseThrow();
        assertThat(idCol.get("data_type").toString()).isEqualTo("uuid");

        // Check type column
        Map<String, Object> typeCol = columns.stream()
            .filter(c -> "type".equals(c.get("column_name")))
            .findFirst().orElseThrow();
        assertThat(typeCol.get("data_type").toString()).contains("character varying");

        // Check data column (JSONB)
        Map<String, Object> dataCol = columns.stream()
            .filter(c -> "data".equals(c.get("column_name")))
            .findFirst().orElseThrow();
        assertThat(dataCol.get("data_type").toString()).isEqualTo("jsonb");

        // Check occurred_at column
        Map<String, Object> occurredCol = columns.stream()
            .filter(c -> "occurred_at".equals(c.get("column_name")))
            .findFirst().orElseThrow();
        assertThat(occurredCol.get("is_nullable").toString()).isEqualTo("NO");
    }

    @Test
    void eventsTableHasExpectedIndexes() {
        List<Map<String, Object>> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'events' ORDER BY indexname");

        List<String> indexNames = indexes.stream()
            .map(m -> (String) m.get("indexname"))
            .toList();

        // Should have indexes on type, process_instance_id, process_definition_id, occurred_at
        assertThat(indexNames).anyMatch(n -> n.contains("type"));
        assertThat(indexNames).anyMatch(n -> n.contains("process_instance_id"));
        assertThat(indexNames).anyMatch(n -> n.contains("process_definition_id"));
        assertThat(indexNames).anyMatch(n -> n.contains("occurred_at"));
    }

    @Test
    void sequenceIsMonotonic() {
        // Insert two events and verify sequence is monotonic
        String uniquePrefix = "test.seq." + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO events (id, type, version, occurred_at, data) VALUES (?, ?, 1, now(), '{}')",
                UUID.randomUUID(), uniquePrefix + ".a");
            jdbc.update("INSERT INTO events (id, type, version, occurred_at, data) VALUES (?, ?, 1, now(), '{}')",
                UUID.randomUUID(), uniquePrefix + ".b");
        });

        List<Long> sequences = jdbc.queryForList(
            "SELECT sequence FROM events WHERE type LIKE ? ORDER BY sequence", Long.class, uniquePrefix + ".%");
        assertThat(sequences).hasSize(2);
        assertThat(sequences.get(1)).isGreaterThan(sequences.get(0));
    }

    @Test
    void jsonbDataColumn_works() {
        String uniqueType = "test.jsonb." + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO events (id, type, version, occurred_at, data) VALUES (?, ?, 1, now(), ?::jsonb)",
                UUID.randomUUID(), uniqueType, "{\"key\": \"value\", \"nested\": {\"a\": 1}}");
        });

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT data::text as data_text FROM events WHERE type = ?", uniqueType);
        assertThat(row.get("data_text").toString()).contains("key").contains("value");
    }
}
