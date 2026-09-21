package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.db.VariableDbOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-3 (#4) — the positional JSON-list write ({@code setJsonElementAt}) on real
 * PostgreSQL. H2 cannot execute the {@code jsonb_set} statement, so the production
 * dialect is proven here against the real {@link VariableDbOperations} bean; the
 * end-to-end MI ordering itself is proven on H2 by
 * {@code MiOutputCollectionDiffIntegrationTests}.
 *
 * <p>POF mutation: route the positional write back through completion-order append
 * ({@code appendJsonElement}) → the padded/overwrite assertions below go RED.
 */
@Tag("pg")
class MiOutputPositionalPgIT extends PostgresIT {

    @Autowired private VariableDbOperations variableDb;
    @Autowired private JdbcTemplate jdbc;

    private UUID sharedPdId;

    @BeforeEach
    void seedProcessDefinition() {
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'mi-pos-pd', 1, 'MI Positional', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanupDefinition() {
        if (sharedPdId != null) {
            jdbc.update("DELETE FROM process_definitions WHERE id = ?", sharedPdId);
            sharedPdId = null;
        }
    }

    private UUID seedProcessInstance() {
        UUID pi = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            pi, sharedPdId, Timestamp.from(Instant.now()));
        return pi;
    }

    private String stored(UUID pi, String name) {
        return jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, pi, name);
    }

    private void cleanup(UUID pi) {
        jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
        jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
    }

    @Test
    void setAtIndex_createsPadsAndOverwrites() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        UUID pi = seedProcessInstance();
        try {
            // Absent row, index 0 → single-element list.
            variableDb.setJsonElementAt(pi, "r", 0, "\"a\"");
            assertThat(mapper.readValue(stored(pi, "r"),
                new tools.jackson.core.type.TypeReference<List<String>>() {})).containsExactly("a");

            // Index 2 on a 1-element list → null-padded, value AT index 2.
            variableDb.setJsonElementAt(pi, "r", 2, "\"c\"");
            List<?> padded = mapper.readValue(stored(pi, "r"), List.class);
            assertThat(padded).hasSize(3);
            assertThat(padded.get(0).toString()).isEqualTo("a");
            assertThat(padded.get(1)).isNull();
            assertThat(padded.get(2).toString()).isEqualTo("c");

            // Overwrite within bounds does not shift neighbours.
            variableDb.setJsonElementAt(pi, "r", 1, "\"b\"");
            assertThat(mapper.readValue(stored(pi, "r"),
                new tools.jackson.core.type.TypeReference<List<String>>() {})).containsExactly("a", "b", "c");

            // Overwrite index 0 keeps length.
            variableDb.setJsonElementAt(pi, "r", 0, "\"A\"");
            assertThat(mapper.readValue(stored(pi, "r"),
                new tools.jackson.core.type.TypeReference<List<String>>() {})).containsExactly("A", "b", "c");
        } finally {
            cleanup(pi);
        }
    }

    @Test
    void setAtIndex_replacesNonArrayWithFreshPaddedList() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        UUID pi = seedProcessInstance();
        try {
            jdbc.update(
                "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
                "VALUES (?, ?, NULL, 's', 'STRING', 'scalar')",
                UUID.randomUUID(), pi);
            variableDb.setJsonElementAt(pi, "s", 1, "\"y\"");
            List<?> values = mapper.readValue(stored(pi, "s"), List.class);
            assertThat(values).hasSize(2);
            assertThat(values.get(0)).isNull();
            assertThat(values.get(1).toString()).isEqualTo("y");
        } finally {
            cleanup(pi);
        }
    }
}
