package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * WO-REL-38 (F15): присваивает commit-ordered {@code feed_position} строкам
 * таблицы {@code events}, чей writer уже завершился.
 *
 * <p>Проблема: IDENTITY-{@code sequence} назначается при INSERT, а не в
 * порядке коммитов. Транзакция T1 берёт sequence n и висит; T2 берёт n+1 и
 * коммитится раньше; consumer с курсором {@code > n+1} навсегда теряет n,
 * когда T1 наконец коммитится. Поэтому курсор ленты — НЕ sequence, а эта
 * позиция, которую ставит одиночный writer только строкам, гарантированно
 * вышедшим из незавершённых транзакций.
 *
 * <p>Безопасность набора (PostgreSQL): safe watermark
 * {@code pg_snapshot_xmin(pg_current_snapshot())} — стандартная гарантия PG:
 * любая транзакция с txid ниже watermark уже либо закоммичена (видна), либо
 * абортнута (не появится никогда). Отбор — {@code feed_position IS NULL} плюс
 * epoch-сравнение {@code xmin::text::bigint < watermark::text::bigint} плюс
 * защита {@code xmin::text::bigint >= 3} (frozenxid 2 у старых строк — всегда
 * допущены). Почему text-каст, а не «нативный xid»: в PG НЕТ оператора
 * {@code xid < xid} вообще (проверено живым psql: есть только {@code =}/
 * {@code <>}; {@code <} есть лишь у {@code xid8}). WO-QW-4 (NEW-16c):
 * {@code pg_snapshot_xmin} возвращает {@code xid8} (64-бит, проверено живым
 * {@code pg_typeof} на PG 16), а не {@code xid}, как утверждала прежняя
 * редакция — text-epoch-сравнение корректно пока 32-битный {@code xmin} и
 * 64-битный watermark в одной эпохе (разница txid меньше ~2 млрд транзакций),
 * см. эскалацию WO-REL-38 в канале.
 * Watermark встроен в сам SELECT (STABLE-функция, один замер на стейтмент) —
 * в Java xid не приезжает и не биндится. Сортировка допущенного набора —
 * по PK {@code sequence}.
 *
 * <p>Порядок внутри допущенного набора: по времени КОММИТА, не по
 * {@code sequence}. IDENTITY выдаётся при INSERT (порядок начал), а лента
 * обязана идти в порядке завершений: сначала закоммиченная T2 (больший
 * sequence), потом T1. Время коммита читается из
 * {@code pg_xact_commit_timestamp(xmin)} — требует включённого
 * {@code track_commit_timestamp} (postmaster-контекст: задаётся в команде
 * запуска PG — прод-compose и CI-pg-compose ниже; без него метка NULL и
 * строка ждёт следующего тика, а не встаёт не туда). Строки с NULL-меткой
 * (коммит вне отслеживания: старые/замороженные) в этот тик НЕ допускаются —
 * тихая неверная позиция хуже задержки; живого потока это не касается (новые
 * коммиты всегда с меткой), исторический мусор добирается вторым проходом по
 * sequence, когда timestamp-проход пуст.
 *
 * <p>H2 (тестовый профиль): {@code xmin} нет — в тестах каждая запись
 * коммитится до вызова, ветка без watermark осознанно тестовая; сортировка —
 * по sequence (= порядку вставки в тесте).
 *
 * <p>Сериализация: весь тик — одна транзакция под transaction-scoped
 * advisory lock ({@code feed-position-assign}, прецедент
 * {@code AdvisoryDeployLock} из WO-SCALE-1) — на N репликах writer один.
 * UPDATE условный ({@code ... AND feed_position IS NULL}, в READ COMMITTED
 * предикат перепроверяется после ожидания лока) — даже гонка двух writer'ов
 * не дублирует позиции, второй молча обновляет 0 строк.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedPositionAssigner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final AdvisoryDeployLock advisoryDeployLock;
    private final com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;

    /**
     * WO-REL-48 (N14): bound of ONE tick — one advisory-locked transaction
     * never takes more than this many rows. 500 keeps the batchUpdate small
     * (memory + lock hold time bounded) while draining a post-downtime
     * backlog in a handful of 2s poll ticks. The remaining backlog is NOT
     * skipped: the next tick continues from the commit-ordered head
     * (the two-pass discipline below always takes the OLDEST eligible rows
     * first, so every pass makes progress on the same global order).
     */
    static final int FEED_BATCH_SIZE = 500;

    private volatile String databaseProduct;

    /**
     * @return сколько строк получили позицию за ВСЕ проходы этого вызова
     *         (0 — нечего делать). Один вызов дренирует весь допущенный
     *         backlog проход за проходом, но КАЖДЫЙ проход — свой батч не
     *         больше {@link #FEED_BATCH_SIZE} со своим SELECT, своим
     *         {@code MAX(feed_position)} и своим batchUpdate: память и
     *         advisory-hold одного прохода ограничены, а порядок и
     *         непрерывность — те же (проходы идут с головы commit-порядка,
     *         base перечитывается каждый раз).
     */
    @Transactional
    public int assignPendingPositions() {
        long startedNanos = System.nanoTime();
        // Single-writer: xact-scoped, умирает с этой транзакцией (тот же
        // контракт, что deploy-пути WO-SCALE-1). На H2 — no-op внутри.
        advisoryDeployLock.acquireForKey("feed-position-assign");

        publishBacklogMetrics();
        int totalAssigned = 0;
        long nextBase = 0;
        while (true) {
            List<Long> eligible = selectEligibleSequences();
            if (eligible.isEmpty()) {
                break;
            }
            Long base = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(feed_position), 0) FROM events", Long.class);
            long next = (base != null ? base : 0L) + 1;

            List<Object[]> batch = new ArrayList<>(eligible.size());
            for (Long sequence : eligible) {
                batch.add(new Object[]{next++, sequence});
            }
            int[] updated = jdbcTemplate.batchUpdate(
                "UPDATE events SET feed_position = ? WHERE sequence = ? AND feed_position IS NULL",
                batch);
            for (int n : updated) {
                totalAssigned += n;
            }
            nextBase = next;
        }
        publishBacklogMetrics();
        bpmMetrics.recordFeedAssignDuration(
            java.time.Duration.ofNanos(System.nanoTime() - startedNanos));
        log.info("Assigned feed positions to {} events (next position {})", totalAssigned, nextBase);
        return totalAssigned;
    }

    /**
     * WO-REL-48 (N14): backlog visibility — published on EVERY tick (before
     * and after the batch), so an operator sees the queue grow BEFORE it
     * becomes a problem. Size = rows still without position; age = oldest
     * unassigned row by {@code occurred_at} (seconds, 0 when empty).
     */
    private void publishBacklogMetrics() {
        try {
            Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE feed_position IS NULL", Long.class);
            bpmMetrics.setFeedBacklog(pending != null ? pending : 0);
            Long ageSeconds = jdbcTemplate.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(occurred_at)))::bigint, 0) "
                + "FROM events WHERE feed_position IS NULL", Long.class);
            bpmMetrics.setFeedAgeMaxSeconds(ageSeconds != null ? ageSeconds : 0);
        } catch (Exception e) {
            // Metrics must never break the assign tick (H2-test profile
            // included — EXTRACT(EPOCH...) is PG-only; on H2 this falls back
            // to silent zero, the tick itself is unaffected).
            log.debug("Feed backlog metrics skipped: {}", e.getMessage());
        }
    }

    private List<Long> selectEligibleSequences() {
        if (isPostgres()) {
            // Проход 1: строки с известной меткой коммита — по времени коммита.
            // WO-REL-48: LIMIT батча С ТЕМ ЖЕ ORDER BY — берутся СТАРЕЙШИЕ по
            // коммиту (голова глобального порядка), следующий тик продолжит с
            // того же места: feed_position IS NULL исключает уже взятых, так
            // что keyset-курсор не нужен — незакрытый хвост сам голова.
            List<Long> byCommitTime = jdbcTemplate.queryForList(
                "SELECT sequence FROM events " +
                "WHERE feed_position IS NULL " +
                "AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint " +
                "AND xmin::text::bigint >= 3 " +
                "AND pg_xact_commit_timestamp(xmin) IS NOT NULL " +
                "ORDER BY pg_xact_commit_timestamp(xmin), sequence " +
                "LIMIT " + FEED_BATCH_SIZE,
                Long.class);
            if (!byCommitTime.isEmpty()) {
                return byCommitTime;
            }
            // Проход 2: только исторический мусор без меток (живой поток всегда
            // с меткой) — по sequence, тем же батчем с головы порядка.
            return jdbcTemplate.queryForList(
                "SELECT sequence FROM events " +
                "WHERE feed_position IS NULL " +
                "AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint " +
                "AND xmin::text::bigint >= 3 " +
                "ORDER BY sequence " +
                "LIMIT " + FEED_BATCH_SIZE,
                Long.class);
        }
        // H2: тестовый профиль без xmin — записи тестов всегда закоммичены.
        return jdbcTemplate.queryForList(
            "SELECT sequence FROM events WHERE feed_position IS NULL ORDER BY sequence "
            + "LIMIT " + FEED_BATCH_SIZE,
            Long.class);
    }

    private boolean isPostgres() {
        String cached = databaseProduct;
        if (cached != null) {
            return "PostgreSQL".equals(cached);
        }
        try (var conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            databaseProduct = product;
            return "PostgreSQL".equals(product);
        } catch (Exception e) {
            log.warn("Failed to detect database product, assuming PostgreSQL", e);
            return true;
        }
    }
}
