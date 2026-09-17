package com.zorrodev.bpm.engine.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional batch processor for retention cleanup.
 * Separated from RetentionJob to avoid self-invocation proxy issue (P-18).
 *
 * Safety: only COMPLETED/CANCELLED instances older than TTL, no active tasks/outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionBatchProcessor {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * WO-REL-33: multi-instance захват через {@code FOR UPDATE SKIP LOCKED} —
     * две реплики никогда не видят одни и те же строки: заблокированная другой
     * репликой строка пропускается, а не ждёт снятия lock'а и не обрабатывается
     * дважды. Тот же паттерн, что уже несут timer/outbox/watchdog-пути
     * (ShedLock не введён сознательно: row-level захват даёт ровно «без дублей»
     * без новой зависимости, lock-таблицы и сериализации проходов; retention
     * идемпотентен — пропущенная строка доберётся следующим проходом).
     * H2 этот синтаксис тоже принимает (проверено тестом), PG — подавно.
     * Транзакция НЕ read-only: PG запрещает SELECT FOR UPDATE в read-only
     * (SQL state 25006, поймано живым PgIT-прогоном) — метод только читает,
     * но row lock требует пишущей транзакции; коммит всё равно ничего не пишет.
     */
    @Transactional
    public List<UUID> findEligibleInstances(Instant cutoff, int batchSize) {
        String sql = "SELECT pi.id FROM process_instances pi " +
            "WHERE (pi.completed_at IS NOT NULL OR pi.cancelled = true) " +
            "AND pi.completed_at < :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id = pi.id AND a.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM user_tasks ut WHERE ut.process_instance_id = pi.id AND ut.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM service_tasks st WHERE st.process_instance_id = pi.id AND st.completed_at IS NULL) " +
            "ORDER BY pi.completed_at ASC LIMIT :limit FOR UPDATE OF pi SKIP LOCKED";
        return jdbc.queryForList(sql,
            new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", batchSize),
            UUID.class);
    }

    /**
     * WO-ENG-17: per-definition cutoff. Старый {@link #findEligibleInstances(Instant, int)}
     * оставлен байт-идентичным (его PgIT'ы — доказательство обратной совместимости);
     * этот метод — новый путь job'а: JOIN к определению инстанса, по одной клаузе
     * равенства на каждый distinct TTL с cutoff, вычисленным в Java (plain
     * Timestamp-сравнения — ноль диалектного риска вместо interval-арифметики
     * в SQL; прецедент динамических клауз — skipClause ниже).
     *
     * <p>Семантика (форсирована §4 WO): у определения задан положительный TTL —
     * чистится по нему; NULL — наследует {@code fallbackDays}; оба пусто/0 —
     * инстанс не чистится никогда (дефолт «ничего не теряем»).
     *
     * <p>Гонка pre-query/main-query (деплой с новым TTL между ними) безопасна:
     * пропущенные строки доберутся следующим проходом (та же идемпотентность,
     * что у SKIP LOCKED-пропусков).
     *
     * @param now          точка отсчёта возраста (тесты фиксируют детерминированно)
     * @param fallbackDays глобальный TTL; {@code <= 0} — фолбэка нет (NULL-определения не чистятся)
     */
    @Transactional
    public List<UUID> findEligibleInstances(Instant now, int fallbackDays, int batchSize) {
        // Belt-and-braces: мусор/0 rejected на деплое, но строка БД правится и руками —
        // неположительный TTL здесь никогда не чистится, а не «чистится сразу».
        List<Integer> distinctTtls = jdbc.queryForList(
            "SELECT DISTINCT history_time_to_live_days FROM process_definitions " +
            "WHERE history_time_to_live_days IS NOT NULL AND history_time_to_live_days > 0",
            new MapSqlParameterSource(), Integer.class);
        StringBuilder sql = new StringBuilder(
            "SELECT pi.id FROM process_instances pi " +
            "JOIN process_definitions pd ON pd.id = pi.process_definition_id " +
            "WHERE (pi.completed_at IS NOT NULL OR pi.cancelled = true) AND (");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", batchSize);
        boolean hasBranch = false;
        int i = 0;
        for (int ttl : distinctTtls) {
            if (hasBranch) {
                sql.append(" OR ");
            }
            sql.append("(pd.history_time_to_live_days = :ttl").append(i)
                .append(" AND pi.completed_at < :cutoff").append(i).append(")");
            params.addValue("ttl" + i, ttl);
            params.addValue("cutoff" + i, Timestamp.from(now.minus(java.time.Duration.ofDays(ttl))));
            i++;
            hasBranch = true;
        }
        if (fallbackDays > 0) {
            if (hasBranch) {
                sql.append(" OR ");
            }
            sql.append("(pd.history_time_to_live_days IS NULL AND pi.completed_at < :fallbackCutoff)");
            params.addValue("fallbackCutoff",
                Timestamp.from(now.minus(java.time.Duration.ofDays(fallbackDays))));
            hasBranch = true;
        }
        if (!hasBranch) {
            // Ни одного TTL нигде: запрос обязан вернуть пусто, а не всё.
            // Невозможное условие вместо раннего return — один exit-путь,
            // FOR UPDATE SKIP LOCKED и сортировка те же, что у старого метода.
            sql.append("1 = 0");
        }
        sql.append(") " +
            "AND NOT EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id = pi.id AND a.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM user_tasks ut WHERE ut.process_instance_id = pi.id AND ut.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM service_tasks st WHERE st.process_instance_id = pi.id AND st.completed_at IS NULL) " +
            "ORDER BY pi.completed_at ASC LIMIT :limit FOR UPDATE OF pi SKIP LOCKED");
        return jdbc.queryForList(sql.toString(), params, UUID.class);
    }

    @Transactional
    public int deleteInstances(List<UUID> instanceIds) {
        if (instanceIds.isEmpty()) return 0;
        MapSqlParameterSource params = new MapSqlParameterSource("ids", instanceIds);

        // WO-REL-9: collect token IDs BEFORE deleting activities (activities.token has no FK,
        // and the old code deleted activities first, so the subquery returned empty).
        List<UUID> tokenIds = jdbc.queryForList(
            "SELECT DISTINCT token FROM activities WHERE process_instance_id IN (:ids) AND token IS NOT NULL",
            params, UUID.class);

        int total = 0;
        total += jdbc.update("DELETE FROM timer_jobs WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM message_subscriptions WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM signal_subscriptions WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM parallel_gateways WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM incidents WHERE activity_id IN (SELECT id FROM activities WHERE process_instance_id IN (:ids))", params);
        total += jdbc.update("DELETE FROM service_tasks WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM user_tasks WHERE process_instance_id IN (:ids)", params);
        // WO-C8-25: done element-listener phases (open ones die with the instance anyway).
        total += jdbc.update("DELETE FROM element_listener_phase WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM variables WHERE process_instance_id IN (:ids)", params);
        // WO-ENG-16: история переменных того же инстанса — иначе retention
        // оставлял бы сирот навсегда (у variable_history нет FK сознательно).
        total += jdbc.update("DELETE FROM variable_history WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM activities WHERE process_instance_id IN (:ids)", params);
        if (!tokenIds.isEmpty()) {
            MapSqlParameterSource tokenParams = new MapSqlParameterSource("ids", tokenIds);
            total += jdbc.update("DELETE FROM tokens WHERE id IN (:ids)", tokenParams);
        }
        total += jdbc.update("DELETE FROM process_instances WHERE id IN (:ids)", params);

        log.info("Retention: deleted {} rows for {} instances (incl. {} tokens)", total, instanceIds.size(), tokenIds.size());
        return total;
    }

    /**
     * WO-PERF-3: deletes orphaned fired boundary timer jobs in bounded batches.
     *
     * Pre-fix boundary jobs were created with NULL process_instance_id, so
     * {@link #deleteInstances(List)} (predicate {@code process_instance_id IN (:ids)}) can never
     * reach them — even after their process instances were purged by retention. They are
     * fired=true, cannot be re-armed, and accumulate forever.
     *
     * The cleanup deliberately lives here instead of a migration-time DELETE:
     * <ul>
     *   <li>batched via {@code LIMIT} — each transaction touches at most {@code batchSize} rows
     *       instead of one unbounded statement over the whole table;</li>
     *   <li>TTL-gated ({@code created_at < cutoff}) — the same age window retention already
     *       applies to instances, so a just-fired row of a still-running cycle is never touched;</li>
     *   <li>no startup block: no changelog lock, no irreversible statement at deploy time.</li>
     * </ul>
     *
     * @param cutoff    delete only rows older than this instant
     * @param batchSize maximum rows deleted in this call
     * @return number of rows deleted
     */
    @Transactional
    public int deleteOrphanedBoundaryTimers(Instant cutoff, int batchSize) {
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff))
            .addValue("limit", batchSize);
        return jdbc.update(
            "DELETE FROM timer_jobs WHERE id IN (" +
            "  SELECT id FROM timer_jobs" +
            "  WHERE process_instance_id IS NULL" +
            "    AND boundary_element_id IS NOT NULL" +
            "    AND fired = true" +
            "    AND created_at < :cutoff" +
            "  LIMIT :limit" +
            ")",
            params);
    }

    /**
     * WO-ACL-3 criterion 8: process submissions in terminal state (APPROVED/REJECTED) older
     * than the TTL become eligible for cleanup. Oldest first, bounded by batch size — the same
     * shape as {@link #findEligibleInstances}, so a retention cycle never scans the whole table.
     *
     * <p>WO-REL-33 F37: {@code skipIds} — уже виденные неудаляемые строки (F09-паттерн из
     * WO-REL-35 наоборот: там курсор стоял, здесь курсор движется мимо плохих строк).
     * Пустой/NULL skip-set — обычный опрос без исключений.
     *
     * @return submission ids eligible for deletion (a partial batch means "no more left")
     */
    @Transactional(readOnly = true)
    public List<UUID> findEligibleSubmissions(Instant cutoff, int batchSize) {
        return findEligibleSubmissions(cutoff, batchSize, java.util.Set.of());
    }

    @Transactional(readOnly = true)
    public List<UUID> findEligibleSubmissions(Instant cutoff, int batchSize, java.util.Collection<UUID> skipIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff))
            .addValue("limit", batchSize);
        String skipClause = "";
        if (skipIds != null && !skipIds.isEmpty()) {
            params.addValue("skipIds", skipIds);
            skipClause = "  AND id NOT IN (:skipIds) ";
        }
        return jdbc.queryForList(
            "SELECT id FROM process_submission " +
            "WHERE status IN ('APPROVED', 'REJECTED') " +
            "  AND submitted_at < :cutoff " +
            skipClause +
            "ORDER BY submitted_at ASC LIMIT :limit",
            params, UUID.class);
    }

    /**
     * Deletes ONE submission in its own transaction. Called per row from RetentionJob so a
     * single failing row (locked, FK race) cannot abort the whole cleanup pass (P-42).
     *
     * @return 1 if a row was deleted, 0 if it vanished concurrently
     */
    @Transactional
    public int deleteSubmission(UUID submissionId) {
        return jdbc.update(
            "DELETE FROM process_submission WHERE id = :id",
            new MapSqlParameterSource("id", submissionId));
    }
}
