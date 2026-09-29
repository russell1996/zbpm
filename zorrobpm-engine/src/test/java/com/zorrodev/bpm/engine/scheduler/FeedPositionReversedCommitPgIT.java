package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * WO-REL-38, критерий 1: две НАСТОЯЩИЕ транзакции с ОБРАТНЫМ порядком коммита —
 * T1 начинается раньше (sequence n) и коммитится ПОЗЖЕ, T2 начинается позже
 * (sequence n+1) и коммитится РАНЬШЕ. Consumer с курсором получает ОБА события
 * в commit-порядке, ни одно не пропущено навсегда.
 *
 * <p>Только реальный PostgreSQL: H2 не воспроизводит ни IDENTITY-межтранзакционный
 * захват, ни {@code xmin}-watermark. Строки теста помечены UUID-типом и чистятся
 * в {@code AfterEach} (сюита делит БД).
 */
class FeedPositionReversedCommitPgIT extends PostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private FeedPositionAssigner assigner;

    @Autowired
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private String typePrefix;

    @AfterEach
    void cleanup() {
        if (typePrefix != null) {
            jdbc.update("DELETE FROM events WHERE type LIKE ?", typePrefix + "%");
        }
    }

    private void insertUncommitted(Connection conn, String type) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events (id, type, version, occurred_at) VALUES (?, ?, 1, now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, type);
            ps.executeUpdate();
        }
    }

    @Test
    void reversedCommitOrder_consumerGetsBothInCommitOrder() throws Exception {
        typePrefix = "rel38.rev." + UUID.randomUUID();
        String firstType = typePrefix + ".first";
        String secondType = typePrefix + ".second";

        // T1 стартует раньше и INSERT'ит раньше (= меньший sequence), но висит.
        Connection t1 = dataSource.getConnection();
        t1.setAutoCommit(false);
        CountDownLatch t1Inserted = new CountDownLatch(1);
        CountDownLatch t1MayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> threadError = new AtomicReference<>();
        Thread t1Holder = new Thread(() -> {
            try {
                insertUncommitted(t1, firstType);
                t1Inserted.countDown();
                // Висим, пока T2 не закоммитится раньше нас.
                if (!t1MayCommit.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("T2 never committed");
                }
                t1.commit();
            } catch (Throwable e) {
                threadError.set(e);
            }
        });
        t1Holder.setDaemon(true);
        t1Holder.start();

        try {
            assertThat(t1Inserted.await(15, TimeUnit.SECONDS))
                .as("T1 must INSERT first (holds the smaller sequence)").isTrue();

            // T2: позже начала, раньше коммита. Отдельное соединение = отдельная
            // транзакция, видит только закоммиченное (T1 ей невидим).
            try (Connection t2 = dataSource.getConnection()) {
                t2.setAutoCommit(false);
                insertUncommitted(t2, secondType);
                t2.commit();
            }
            t1MayCommit.countDown();
            t1Holder.join(15_000);

            if (threadError.get() != null) {
                fail("T1 holder thread failed", threadError.get());
            }

            // Порядок sequence — прямой (T1 раньше): доказываем саму постановку.
            List<Map<String, Object>> seqRows = jdbc.queryForList(
                "SELECT type, sequence FROM events WHERE type LIKE ? ORDER BY sequence",
                typePrefix + "%");
            assertThat(seqRows).hasSize(2);
            assertThat(seqRows.get(0).get("type")).isEqualTo(firstType);
            assertThat(seqRows.get(1).get("type")).isEqualTo(secondType);
            long seqFirst = ((Number) seqRows.get(0).get("sequence")).longValue();
            long seqSecond = ((Number) seqRows.get(1).get("sequence")).longValue();
            assertThat(seqFirst).isLessThan(seqSecond);

            // Джоб назначает позиции (обе транзакции завершены — набор допущен).
            int assigned = assigner.assignPendingPositions();
            assertThat(assigned).isGreaterThanOrEqualTo(2);

            List<Map<String, Object>> fpRows = jdbc.queryForList(
                "SELECT type, sequence, feed_position FROM events WHERE type LIKE ? ORDER BY feed_position",
                typePrefix + "%");
            assertThat(fpRows).hasSize(2);
            // Commit-порядок: закоммиченный раньше T2 — раньше в ленте,
            // хотя его sequence больше.
            assertThat(fpRows.get(0).get("type")).isEqualTo(secondType);
            assertThat(fpRows.get(1).get("type")).isEqualTo(firstType);
            long fpFirst = ((Number) fpRows.stream()
                .filter(r -> firstType.equals(r.get("type"))).findFirst().orElseThrow()
                .get("feed_position")).longValue();
            long fpSecond = ((Number) fpRows.stream()
                .filter(r -> secondType.equals(r.get("type"))).findFirst().orElseThrow()
                .get("feed_position")).longValue();
            assertThat(fpSecond).isLessThan(fpFirst);

            // Consumer с курсором 0 получает ОБА, в порядке ленты.
            List<Map<String, Object>> envelopes =
                eventQueryService.findEventEnvelopes(0L, null, null, null, 100);
            List<Map<String, Object>> mine = envelopes.stream()
                .filter(e -> String.valueOf(e.get("type")).startsWith(typePrefix))
                .toList();
            assertThat(mine).hasSize(2);
            assertThat(mine.get(0).get("type")).isEqualTo(secondType);
            assertThat(mine.get(1).get("type")).isEqualTo(firstType);
            assertThat(mine.get(0).get("feedPosition")).isEqualTo(fpSecond);
            assertThat(mine.get(1).get("feedPosition")).isEqualTo(fpFirst);
        } finally {
            t1MayCommit.countDown();
            try {
                if (!t1.getAutoCommit()) {
                    t1.rollback();
                }
            } catch (Exception ignored) {
                // Уже закоммичена счастливым путём — нечего откатывать.
            }
            t1.close();
        }
    }
}
