package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-31 CR-1: setVariables must be an upsert, not a select-then-insert.
 *
 * <p>Before CR-1 the root scope (scope_id IS NULL) had NO physical uniqueness
 * (PostgreSQL treats every NULL as distinct — see changeset 038 comment: "root
 * uniqueness is kept by the application upsert (find-by-scope-null before
 * insert)"). Two concurrent setVariables(root) for the same variable both find
 * nothing and both insert: with the new NULLS NOT DISTINCT unique key
 * (changeset 108) the second insert dies with DuplicateKeyException, and even
 * against the old key two rows would silently survive.
 *
 * <p>Rounds: a single two-thread race may miss the find-then-insert window (the
 * loser thread may start only after the winner has committed — then the old
 * find+save path honestly updates). Repeating the race over fresh keys makes the
 * RED on the old path overwhelmingly likely, while the upsert path is
 * deterministically GREEN (an ON CONFLICT upsert never throws and always leaves
 * exactly one row).
 *
 * <p>POF (G-N): reverting the upsert to the old find-by-scope-null + saveAll
 * makes this test RED (DuplicateKeyException in at least one round). The
 * production path is exercised end-to-end via {@link
 * com.zorrodev.bpm.engine.service.db.VariableDbOperations#setVariables(UUID, List)}.
 */
@Tag("pg")
class VariableUpsertRacePgIT extends PostgresIT {

    private static final int ROUNDS = 30;

    @Autowired private com.zorrodev.bpm.engine.service.db.VariableDbOperations variableDb;
    @Autowired private JdbcTemplate jdbc;

    private UUID sharedPdId;
    /** Every seeded pi (paired with its variable name) is removed after the test. */
    private final CopyOnWriteArrayList<UUID> cleanupPis = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seedProcessDefinition() {
        // FK target for process_instances; one shared row is enough for all rounds.
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'upsert-race-pd', 1, 'Upsert Race', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanup() {
        for (UUID pi : cleanupPis) {
            jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
        }
        cleanupPis.clear();
        if (sharedPdId != null) {
            jdbc.update("DELETE FROM process_definitions WHERE id = ?", sharedPdId);
            sharedPdId = null;
        }
    }

    private UUID seedProcessInstance() {
        UUID pi = UUID.randomUUID();
        cleanupPis.add(pi);
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            pi, sharedPdId, Timestamp.from(Instant.now()));
        return pi;
    }

    @Test
    void concurrentSetVariables_root_sameName_yieldsExactlyOneRowEveryRound() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID pi = seedProcessInstance();
            String name = "race-var-" + round + "-" + UUID.randomUUID().toString().substring(0, 8);

            ProcessVariable pvA = new ProcessVariable();
            pvA.setName(name);
            pvA.setType(ProcessVariableType.STRING);
            pvA.setValue("v-a-" + round);

            ProcessVariable pvB = new ProcessVariable();
            pvB.setName(name);
            pvB.setType(ProcessVariableType.STRING);
            pvB.setValue("v-b-" + round);

            CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
            race(errors, () -> variableDb.setVariables(pi, List.of(pvA)),
                       () -> variableDb.setVariables(pi, List.of(pvB)));

            // CR-1 contract: neither thread fails and exactly one row survives.
            assertThat(errors)
                .as("round %d: no setVariables failed: %s", round, errors)
                .isEmpty();
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND name = ?",
                Integer.class, pi, name);
            assertThat(count).as("round %d: exactly one row", round).isEqualTo(1);
        }
    }

    private void race(CopyOnWriteArrayList<Throwable> errors, Runnable a, Runnable b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread t0 = new Thread(() -> {
            try {
                start.await();
                a.run();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                b.run();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        t0.start();
        t1.start();
        start.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
            .as("both setVariables finished").isTrue();
    }
}