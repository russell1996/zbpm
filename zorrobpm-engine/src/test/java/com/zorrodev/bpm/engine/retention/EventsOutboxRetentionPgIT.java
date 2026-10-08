package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.event.SseLiveCursorTracker;
import com.zorrodev.bpm.engine.service.EventQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-7: retention таблиц {@code events} и {@code outbox} на реальном
 * PostgreSQL (критерии 1–4 WO — все тестами здесь, таблицей КЛАССОВ строк,
 * а не штучными кейсами).
 *
 * <p>Классы строк events: старая-назначенная-ниже-пина (удаляется), свежая
 * (цела), старая-без-позиции (цела — consumer её не видел), старая-выше-пина
 * (цела — медленный подписчик), строка-якорь с MAX feed_position (цела —
 * монотонность счётчика assigner'а), границы ровно-TTL (цела, строгое
 * {@code <}), TTL−1с (удаляется), TTL+1с (цела).
 *
 * <p>Классы строк outbox: старые-терминальные (published любым статусом +
 * карантин FAILED — удаляются), старые-активные (PENDING + unpublished —
 * целы ПРИ ЛЮБОМ возрасте, их забирает только поллер), свежие любых
 * статусов (целы).
 */
public class EventsOutboxRetentionPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;
    @Autowired EventQueryService eventQueryService;
    @Autowired SseLiveCursorTracker cursorTracker;

    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF_30D = NOW.minusSeconds(30L * 86400);

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE events, outbox RESTART IDENTITY");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanAfter() {
        // bulk300k оставляет ~300k фоновых строк: чистим за собой, чтобы не
        // тормозить/ломать чужие PgIT-классы в том же прогоне (общая БД).
        jdbc.execute("TRUNCATE events, outbox RESTART IDENTITY");
    }

    private void insertEvent(Instant occurredAt, Long feedPosition) {
        jdbc.update("INSERT INTO events (id, type, occurred_at, feed_position) VALUES (?, 'audit7', ?, ?)",
            UUID.randomUUID(), Timestamp.from(occurredAt), feedPosition);
    }

    private void insertOutbox(Instant createdAt, boolean published, String status, int attempts) {
        jdbc.update("INSERT INTO outbox (id, payload, created_at, published, status, attempts) "
            + "VALUES (?, '{}', ?, ?, ?, ?)",
            UUID.randomUUID(), Timestamp.from(createdAt), published, status, attempts);
    }

    private List<Long> remainingFeedPositions() {
        return jdbc.queryForList(
            "SELECT feed_position FROM events WHERE feed_position IS NOT NULL ORDER BY feed_position",
            Long.class);
    }

    private int countEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
    }

    private int countOutbox() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    }

    // ==================== Критерий 1: таблица классов events ====================

    @Test
    void events_oldAssignedBelowPin_deletedRestKept() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        // fp 1..3 — старые назначенные ниже пина (пин 5): кандидаты.
        insertEvent(old, 1L);
        insertEvent(old, 2L);
        insertEvent(old, 3L);
        // fp 4 — старая выше пина: медленный подписчик (пина = min = 3? нет —
        // пин 3 держит <= 3; fp 4 выше пина → цела). Пин ставим 3.
        insertEvent(old, 4L);
        // NULL-позиция старая: джоб не назначил — цела.
        insertEvent(old, null);
        // Свежая назначенная: цела.
        insertEvent(NOW.minusSeconds(60), 6L);
        // Якорь: MAX-позиция (7) при старом occurred — цела (монотонность).
        insertEvent(old, 7L);

        RetentionBatchProcessor.EventsBatch done =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.of(3L));

        assertThat(done.rowsDeleted()).isEqualTo(3);
        assertThat(remainingFeedPositions()).containsExactly(4L, 6L, 7L);
        assertThat(countEvents()).isEqualTo(4); // 3 назначенные выше пина/якорь + 1 NULL-строка
    }

    @Test
    void events_boundary_exactlyTtl_keptOneSecondOlder_deleted() {
        insertEvent(CUTOFF_30D, 1L); // ровно TTL: строгое < — цела
        insertEvent(CUTOFF_30D.minusSeconds(1), 2L); // TTL−1с: удаляется
        insertEvent(CUTOFF_30D.plusSeconds(1), 3L); // TTL+1с: цела
        insertEvent(CUTOFF_30D.minusSeconds(3600), 4L); // якорь MAX: цел

        RetentionBatchProcessor.EventsBatch done =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.empty());

        assertThat(done.rowsDeleted()).isEqualTo(1);
        assertThat(remainingFeedPositions()).containsExactly(1L, 3L, 4L);
    }

    @Test
    void events_maxPositionAnchor_neverDeletedEvenWithoutPin() {
        Instant old = CUTOFF_30D.minusSeconds(86400);
        insertEvent(old, 10L);
        insertEvent(old, 11L);

        RetentionBatchProcessor.EventsBatch done =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.empty());

        // fp 11 — текущая MAX: якорь цел; fp 10 — удалена.
        assertThat(done.rowsDeleted()).isEqualTo(1);
        assertThat(remainingFeedPositions()).containsExactly(11L);
    }

    @Test
    void events_dryRun_countsWithoutDeleting() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        insertEvent(old, 1L);
        insertEvent(old, 2L);
        insertEvent(old, 3L); // MAX-якорь

        long would = batchProcessor.countEligibleEvents(CUTOFF_30D, OptionalLong.empty());

        assertThat(would).isEqualTo(2L);
        assertThat(countEvents()).isEqualTo(3);
    }

    @Test
    void events_rerun_isIdempotent() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        insertEvent(old, 1L);
        insertEvent(old, 2L);

        RetentionBatchProcessor.EventsBatch first =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.empty());
        RetentionBatchProcessor.EventsBatch second =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.empty());

        assertThat(first.rowsDeleted()).isEqualTo(1); // fp 2 — якорь
        assertThat(second.rowsDeleted()).isEqualTo(0);
        assertThat(second.sequences()).isEmpty();
        assertThat(remainingFeedPositions()).containsExactly(2L);
    }

    @Test
    void events_batchesAreBounded() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        // fp 1..5, MAX=5 — eligible 1..4.
        for (long fp = 1; fp <= 5; fp++) {
            insertEvent(old, fp);
        }
        RetentionBatchProcessor.EventsBatch b1 =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 2, OptionalLong.empty());
        RetentionBatchProcessor.EventsBatch b2 =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 2, OptionalLong.empty());
        RetentionBatchProcessor.EventsBatch b3 =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 2, OptionalLong.empty());

        assertThat(b1.rowsDeleted()).isEqualTo(2);
        assertThat(b2.rowsDeleted()).isEqualTo(2);
        assertThat(b3.rowsDeleted()).isEqualTo(0);
        assertThat(remainingFeedPositions()).containsExactly(5L);
    }

    // ==================== Критерий 1: таблица классов outbox ====================

    @Test
    void outbox_onlyOldTerminal_deletedActiveAlwaysKept() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        Instant fresh = NOW.minusSeconds(60);
        insertOutbox(old, true, "PENDING", 0); // старый опубликованный — удалить
        insertOutbox(old, true, "FAILED", 3); // старый опубликованный карантинный — удалить
        insertOutbox(old, false, "FAILED", 5); // старый карантин (неопубликован) — удалить
        insertOutbox(old, false, "PENDING", 0); // старый АКТИВНЫЙ (ждёт поллер) — ЦЕЛ
        insertOutbox(old, false, "PENDING", 5); // старый ретраящийся — ЦЕЛ
        insertOutbox(fresh, true, "PENDING", 0); // свежий опубликованный — цел
        insertOutbox(fresh, false, "FAILED", 5); // свежий карантин — цел
        insertOutbox(fresh, false, "PENDING", 0); // свежий активный — цел

        RetentionBatchProcessor.OutboxBatch done =
            batchProcessor.claimAndDeleteOutboxBatch(CUTOFF_30D, 100);

        assertThat(done.rowsDeleted()).isEqualTo(3);
        assertThat(countOutbox()).isEqualTo(5);
        // Активные строки живы независимо от возраста и попыток.
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE published = false AND status = 'PENDING'", Integer.class))
            .isEqualTo(3);
    }

    @Test
    void outbox_rerun_isIdempotent() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        insertOutbox(old, true, "PENDING", 0);

        RetentionBatchProcessor.OutboxBatch first =
            batchProcessor.claimAndDeleteOutboxBatch(CUTOFF_30D, 100);
        RetentionBatchProcessor.OutboxBatch second =
            batchProcessor.claimAndDeleteOutboxBatch(CUTOFF_30D, 100);

        assertThat(first.rowsDeleted()).isEqualTo(1);
        assertThat(second.rowsDeleted()).isEqualTo(0);
        assertThat(countOutbox()).isEqualTo(0);
    }

    @Test
    void outbox_dryRun_countsWithoutDeleting() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        insertOutbox(old, true, "PENDING", 0);
        insertOutbox(old, false, "FAILED", 2);
        insertOutbox(old, false, "PENDING", 0);

        assertThat(batchProcessor.countEligibleOutbox(CUTOFF_30D)).isEqualTo(2L);
        assertThat(countOutbox()).isEqualTo(3);
    }

    // ==================== Критерий 2: пин + catchup ====================

    @Test
    void pinFromRealTracker_holdsRowsAboveIt_releasesAfterAdvance() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        for (long fp = 1; fp <= 5; fp++) {
            insertEvent(old, fp);
        }
        cursorTracker.track("slow-client", 2L);
        try {
            RetentionBatchProcessor.EventsBatch held =
                batchProcessor.claimAndDeleteEventsBatch(
                    CUTOFF_30D, 100, cursorTracker.minActiveCursor());
            // Удалены только fp <= 2 (и < MAX=5): 1, 2. Выше пина — целы.
            assertThat(held.rowsDeleted()).isEqualTo(2);
            assertThat(remainingFeedPositions()).containsExactly(3L, 4L, 5L);

            // catchup медленного клиента видит свои строки: пин ушёл до 4 —
            // fp 3, 4 обязаны быть на месте.
            cursorTracker.advance("slow-client", 4L);
            RetentionBatchProcessor.EventsBatch released =
                batchProcessor.claimAndDeleteEventsBatch(
                    CUTOFF_30D, 100, cursorTracker.minActiveCursor());
            assertThat(released.rowsDeleted()).isEqualTo(2);
            assertThat(remainingFeedPositions()).containsExactly(5L);
        } finally {
            cursorTracker.untrack("slow-client");
        }
    }

    @Test
    void catchupAfterRetention_seesSurvivorsInOrder() {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        insertEvent(old, 1L);
        insertEvent(old, 2L);
        insertEvent(old, 3L); // MAX-якорь
        insertEvent(NOW.minusSeconds(60), 4L);
        insertEvent(NOW.minusSeconds(30), 5L); // MAX-якорь свежий

        batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 100, OptionalLong.of(1L));

        // Клиент с курсором 1 делает catchup: окно (1, +inf) — выжившие
        // 2, 3, 4, 5 в порядке позиций, без дыр и зависаний.
        List<Map<String, Object>> envelopes =
            eventQueryService.findEventEnvelopes(1L, null, null, null, 100);
        assertThat(envelopes).extracting(e -> ((Number) e.get("feedPosition")).longValue())
            .containsExactly(2L, 3L, 4L, 5L);
    }

    // ==================== Критерий 3: конкурентный claim + 300k замер ====================

    @Test
    void concurrentClaim_twoThreads_neverSeeSameRows() throws Exception {
        Instant old = CUTOFF_30D.minusSeconds(3600);
        // 20 строк fp 1..20 (MAX=20 — eligible 19).
        for (long fp = 1; fp <= 20; fp++) {
            insertEvent(old, fp);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<List<Long>> seenA = new AtomicReference<>(List.of());
        AtomicReference<List<Long>> seenB = new AtomicReference<>(List.of());
        try {
            var fa = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                List<Long> all = new java.util.ArrayList<>();
                while (true) {
                    var b = batchProcessor.claimAndDeleteEventsBatch(
                        CUTOFF_30D, 7, OptionalLong.empty());
                    if (b.sequences().isEmpty()) break;
                    all.addAll(b.sequences());
                }
                seenA.set(all);
                return null;
            });
            var fb = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                List<Long> all = new java.util.ArrayList<>();
                while (true) {
                    var b = batchProcessor.claimAndDeleteEventsBatch(
                        CUTOFF_30D, 7, OptionalLong.empty());
                    if (b.sequences().isEmpty()) break;
                    all.addAll(b.sequences());
                }
                seenB.set(all);
                return null;
            });
            start.countDown();
            fa.get(120, TimeUnit.SECONDS);
            fb.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 19 eligible-строк удалены ровно по одному разу (SKIP LOCKED, V6 —
        // два реальных потока, не sequential-имитация).
        assertThat(seenA.get()).doesNotContainAnyElementsOf(seenB.get());
        assertThat(seenA.get().size() + seenB.get().size()).isEqualTo(19);
        assertThat(remainingFeedPositions()).containsExactly(20L);
    }

    @Test
    void bulk300k_claimUsesOccurredAtIndex_batchBounded() {
        // 299000 свежих NULL-строк (фон, как живой backlog assigner'а) + 1000 старых.
        jdbc.update("INSERT INTO events (id, type, occurred_at, feed_position) "
            + "SELECT gen_random_uuid(), 'audit7-bulk', now(), NULL FROM generate_series(1, 299000) g");
        jdbc.update("INSERT INTO events (id, type, occurred_at, feed_position) "
            + "SELECT gen_random_uuid(), 'audit7-bulk', now() - interval '60 days', 5000 + g "
            + "FROM generate_series(1, 1000) g");

        List<String> plan = jdbc.queryForList("EXPLAIN SELECT sequence FROM events "
            + "WHERE occurred_at < now() - interval '30 days' "
            + "AND feed_position IS NOT NULL "
            + "AND feed_position < (SELECT COALESCE(MAX(feed_position), 0) FROM events) "
            + "ORDER BY occurred_at ASC LIMIT 1000", String.class);
        // WO-AUDIT-7: отдельной миграции-индекса НЕ нужно — планировщик идёт
        // по существующим индексам (uq_events__feed_position и/или
        // idx_events_occurred_at, зависит от распределения). Требование одно:
        // никакого Seq Scan на 300k (батч обязан оставаться ограниченным).
        String planText = String.join("\n", plan);
        assertThat(planText).doesNotContain("Seq Scan on events");
        assertThat(planText).containsAnyOf("idx_events_occurred_at", "uq_events__feed_position");

        long t0 = System.nanoTime();
        long eligible = batchProcessor.countEligibleEvents(CUTOFF_30D, OptionalLong.empty());
        long countMs = (System.nanoTime() - t0) / 1_000_000;
        // 1000 старых минус якорь MAX (6000).
        assertThat(eligible).isEqualTo(999L);
        System.out.println("AUDIT7-BULK count eligible on 300k rows: " + countMs + " ms");

        t0 = System.nanoTime();
        RetentionBatchProcessor.EventsBatch b =
            batchProcessor.claimAndDeleteEventsBatch(CUTOFF_30D, 1000, OptionalLong.empty());
        long batchMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(b.rowsDeleted()).isLessThanOrEqualTo(1000);
        assertThat(b.rowsDeleted()).isEqualTo(999);
        System.out.println("AUDIT7-BULK claim+delete batch(1000) on 300k rows: " + batchMs + " ms");
    }
}
