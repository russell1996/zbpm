package com.zorrodev.bpm.engine.scheduler;

import org.junit.jupiter.api.BeforeEach;
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
 * WO-REL-38: {@link FeedPositionAssigner} на H2 — порядок, непрерывность,
 * идемпотентность и условность UPDATE. Реальная колонка (миграция 114
 * применяется Liquibase при старте контекста), реальный бин, реальный JDBC.
 *
 * <p>Watermark-ветка ({@code xmin}) — только PostgreSQL; H2-ветка берёт все
 * NULL-строки (в тестах каждая запись закоммичена до вызова). Обратный порядок
 * коммитов честно воспроизводим только на PG
 * ({@code FeedPositionReversedCommitPgIT}) — здесь структура присвоения.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedPositionAssignerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FeedPositionAssigner assigner;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM events");
    }

    private void insert(String type) {
        jdbc.update("INSERT INTO events (id, type, version, occurred_at) VALUES (?, ?, 1, CURRENT_TIMESTAMP)",
            UUID.randomUUID(), type);
    }

    private List<Map<String, Object>> positions(String typePrefix) {
        return jdbc.queryForList(
            "SELECT sequence, feed_position AS fp FROM events WHERE type LIKE ? ORDER BY sequence",
            typePrefix + "%");
    }

    @Test
    void assignsSequentialPositionsInSequenceOrder() {
        String prefix = "rel38.order." + UUID.randomUUID();
        insert(prefix + ".a");
        insert(prefix + ".b");
        insert(prefix + ".c");

        int assigned = assigner.assignPendingPositions();

        assertThat(assigned).isEqualTo(3);
        List<Map<String, Object>> rows = positions(prefix);
        assertThat(rows).hasSize(3);
        List<Long> fps = rows.stream().map(r -> ((Number) r.get("FP")).longValue()).toList();
        assertThat(fps).doesNotContainNull();
        List<Long> seqs = rows.stream().map(r -> ((Number) r.get("SEQUENCE")).longValue()).toList();
        assertThat(seqs).isSorted();
        // Позиции идут в порядке sequence и непрерывны (база пуста — MAX был 0).
        assertThat(fps).containsExactly(
            fps.get(0), fps.get(0) + 1, fps.get(0) + 2);
        for (int i = 0; i < seqs.size() - 1; i++) {
            assertThat(seqs.get(i)).isLessThan(seqs.get(i + 1));
        }
    }

    @Test
    void secondTickIsNoop_positionsStable() {
        String prefix = "rel38.idem." + UUID.randomUUID();
        insert(prefix + ".a");
        assigner.assignPendingPositions();
        List<Long> before = positions(prefix).stream()
            .map(r -> ((Number) r.get("FP")).longValue()).toList();

        int assigned = assigner.assignPendingPositions();

        assertThat(assigned).isZero();
        List<Long> after = positions(prefix).stream()
            .map(r -> ((Number) r.get("FP")).longValue()).toList();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void onlyNullRowsTouched_existingPositionsKept() {
        String prefix = "rel38.cond." + UUID.randomUUID();
        insert(prefix + ".a");
        insert(prefix + ".b");
        assigner.assignPendingPositions();
        // Новый тик видит одну новую строку: MAX продолжается, старые не тронуты.
        insert(prefix + ".c");

        int assigned = assigner.assignPendingPositions();

        assertThat(assigned).isEqualTo(1);
        List<Map<String, Object>> rows = positions(prefix);
        assertThat(rows).hasSize(3);
        List<Long> fps = rows.stream().map(r -> ((Number) r.get("FP")).longValue()).toList();
        assertThat(fps).doesNotContainNull();
        assertThat(fps).isSorted();
    }
}
