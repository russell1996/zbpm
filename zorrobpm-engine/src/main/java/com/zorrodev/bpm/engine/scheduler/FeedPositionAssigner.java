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
 * {@code <>}; {@code <} есть лишь у {@code xid8}, а {@code pg_snapshot_xmin}
 * возвращает {@code xid}) — text-epoch-сравнение корректно пока разница txid
 * меньше пол-эпохи (~2 млрд транзакций), см. эскалацию WO-REL-38 в канале.
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

    private volatile String databaseProduct;

    /**
     * @return сколько строк получили позицию за тик (0 — нечего делать)
     */
    @Transactional
    public int assignPendingPositions() {
        // Single-writer: xact-scoped, умирает с этой транзакцией (тот же
        // контракт, что deploy-пути WO-SCALE-1). На H2 — no-op внутри.
        advisoryDeployLock.acquireForKey("feed-position-assign");

        List<Long> eligible = selectEligibleSequences();
        if (eligible.isEmpty()) {
            return 0;
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
        int assigned = 0;
        for (int n : updated) {
            assigned += n;
        }
        log.info("Assigned feed positions to {} events (next position {})", assigned, next);
        return assigned;
    }

    private List<Long> selectEligibleSequences() {
        if (isPostgres()) {
            // Проход 1: строки с известной меткой коммита — по времени коммита.
            List<Long> byCommitTime = jdbcTemplate.queryForList(
                "SELECT sequence FROM events " +
                "WHERE feed_position IS NULL " +
                "AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint " +
                "AND xmin::text::bigint >= 3 " +
                "AND pg_xact_commit_timestamp(xmin) IS NOT NULL " +
                "ORDER BY pg_xact_commit_timestamp(xmin), sequence",
                Long.class);
            if (!byCommitTime.isEmpty()) {
                return byCommitTime;
            }
            // Проход 2: только исторический мусор без меток (живой поток всегда
            // с меткой) — по sequence. Отдельным проходом, а не NULLS LAST в
            // одном SELECT: иначе вечный хвост NULL-строк вставал бы ПЕРЕД...
            // нет, NULLS LAST — после; но один проход смешивал бы эпохи. Два
            // прохода: сначала весь известный порядок, потом мусор.
            return jdbcTemplate.queryForList(
                "SELECT sequence FROM events " +
                "WHERE feed_position IS NULL " +
                "AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint " +
                "AND xmin::text::bigint >= 3 " +
                "ORDER BY sequence",
                Long.class);
        }
        // H2: тестовый профиль без xmin — записи тестов всегда закоммичены.
        return jdbcTemplate.queryForList(
            "SELECT sequence FROM events WHERE feed_position IS NULL ORDER BY sequence",
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
