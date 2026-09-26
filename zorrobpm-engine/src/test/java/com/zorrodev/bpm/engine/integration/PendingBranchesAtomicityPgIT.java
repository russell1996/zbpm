package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-40 (B-5): the pending-branches decrement is atomic.
 *
 * <p>Before the fix, {@code decrementPendingBranches} was SELECT-then-UPDATE:
 * N branches finishing in parallel read the same counter and write back the
 * same decremented value — a lost update. The final branch is then never
 * observed (counter never reaches 0 → the join hangs) or observed twice.
 *
 * <p>Rounds: a single race may miss the read/write window (the loser may start
 * only after the winner committed). Repeating the race over fresh tokens makes
 * RED on the old path overwhelmingly likely, while the row-locked path is
 * deterministically GREEN.
 *
 * <p>POF (G-N): reverting the row lock ({@code findByIdForUpdate} → plain
 * {@code findById}) makes this test RED (duplicate/never-zero countdown).
 * The production path is exercised end-to-end via the real
 * {@link ParallelGatewayDbOperations} bean.
 */
@Tag("pg")
class PendingBranchesAtomicityPgIT extends PostgresIT {

    /** At most the Hikari pool size of the pgtest profile (10) — every racer needs a connection. */
    private static final int BRANCHES = 8;
    private static final int ROUNDS = 25;

    @Autowired private ParallelGatewayDbOperations parallelGatewayDb;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentDecrement_eachBranchConsumedExactlyOnceEveryRound() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID tokenId = UUID.randomUUID();
            jdbc.update("INSERT INTO tokens (id, pending_branches) VALUES (?, ?)", tokenId, BRANCHES);

            CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(BRANCHES);
            for (int i = 0; i < BRANCHES; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        seen.add(parallelGatewayDb.decrementPendingBranches(tokenId));
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

            // B-5 contract: nobody failed, the countdown ran exactly BRANCHES…0
            // with no value lost (duplicate) and no value skipped, and the last
            // branch observed exactly one zero.
            assertThat(errors).as("round %d: no decrement failed: %s", round, errors).isEmpty();
            assertThat(seen)
                .as("round %d: every branch consumed exactly once", round)
                .containsExactlyInAnyOrder(IntStream.range(0, BRANCHES).boxed().toArray(Integer[]::new));
            Integer stored = jdbc.queryForObject(
                "SELECT pending_branches FROM tokens WHERE id = ?", Integer.class, tokenId);
            assertThat(stored).as("round %d: counter drained to zero", round).isZero();

            jdbc.update("DELETE FROM tokens WHERE id = ?", tokenId);
        }
    }
}
