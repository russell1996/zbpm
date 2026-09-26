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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-41 (B-8, п.1): the MI output-collection append is atomic.
 *
 * <p>Before the fix, {@code ElementSupport.appendToJsonList} was Java-side
 * read-modify-write (full read, merge in Java, full overwrite): N branches
 * completing in parallel read the same list and each wrote back only its own
 * element — lost updates. Now the whole merge happens in ONE SQL statement
 * ({@code ::jsonb ||} on PostgreSQL), so every concurrent completion lands.
 *
 * <p>Rounds: a single race may miss the read/write window (the loser may start
 * only after the winner committed). Repeating the race over fresh variables
 * makes RED on the old path overwhelmingly likely, while the atomic path is
 * deterministically GREEN.
 *
 * <p>POF (G-N): reverting {@code ElementSupport.appendToJsonList} to the old
 * read-then-write makes this test RED (fewer than N elements survive). The
 * production path is exercised end-to-end via the real
 * {@link VariableDbOperations} bean.
 */
@Tag("pg")
class MiAggregationRacePgIT extends PostgresIT {

    /** At most the Hikari pool size of the pgtest profile (10). */
    private static final int BRANCHES = 10;
    private static final int ROUNDS = 12;

    @Autowired private VariableDbOperations variableDb;
    @Autowired private JdbcTemplate jdbc;

    private UUID sharedPdId;

    @BeforeEach
    void seedProcessDefinition() {
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'mi-agg-race-pd', 1, 'MI Agg Race', ?, ?)",
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

    @Test
    void concurrentAppend_everyBranchLandsEveryRound() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        for (int round = 0; round < ROUNDS; round++) {
            UUID pi = seedProcessInstance();
            final String roundTag = "agg-" + round;

            CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(BRANCHES);
            for (int i = 0; i < BRANCHES; i++) {
                final String elem = "\"e-" + round + "-" + i + "\"";
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        variableDb.appendJsonElement(pi, roundTag, elem);
                    } catch (Throwable th) {
                        errors.add(th);
                    } finally {
                        done.countDown();
                    }
                });
                t.start();
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                .as("round %d: all branches finished", round).isTrue();

            assertThat(errors).as("round %d: no append failed: %s", round, errors).isEmpty();
            String stored = jdbc.queryForObject(
                "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ?",
                String.class, pi, roundTag);
            List<String> values = mapper.readValue(stored,
                new tools.jackson.core.type.TypeReference<List<String>>() {});
            final int r = round;
            List<String> expected = IntStream.range(0, BRANCHES)
                .mapToObj(i -> "e-" + r + "-" + i).toList();
            assertThat(values)
                .as("round %d: every branch element survived", round)
                .containsExactlyInAnyOrderElementsOf(expected);

            jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
        }
    }

    @Test
    void appendSemantics_createExtendReplaceNest() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        UUID pi = seedProcessInstance();
        try {
            // Absent row → fresh single-element list.
            variableDb.appendJsonElement(pi, "a", "\"x\"");
            assertThat(mapper.readValue(jdbc.queryForObject(
                "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ?",
                String.class, pi, "a"),
                new tools.jackson.core.type.TypeReference<List<String>>() {})).containsExactly("x");

            // Stored array → extended.
            variableDb.appendJsonElement(pi, "a", "2");
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND name = ?",
                Integer.class, pi, "a")).isEqualTo(1);

            // Stored scalar → fresh single-element list (old code: same fail-open).
            jdbc.update(
                "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
                "VALUES (?, ?, NULL, 's', 'STRING', 'scalar')",
                UUID.randomUUID(), pi);
            variableDb.appendJsonElement(pi, "s", "\"y\"");
            assertThat(mapper.readValue(jdbc.queryForObject(
                "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ?",
                String.class, pi, "s"),
                new tools.jackson.core.type.TypeReference<List<String>>() {})).containsExactly("y");

            // Array-valued element nests as ONE element (list.add, not concat).
            variableDb.appendJsonElement(pi, "n", "[1,2]");
            List<?> nested = mapper.readValue(jdbc.queryForObject(
                "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ?",
                String.class, pi, "n"), List.class);
            assertThat(nested).hasSize(1);
            assertThat(nested.get(0).toString()).isEqualTo("[1, 2]");
        } finally {
            jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
        }
    }
}
