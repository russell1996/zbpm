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
     * @return submission ids eligible for deletion (a partial batch means "no more left")
     */
    @Transactional(readOnly = true)
    public List<UUID> findEligibleSubmissions(Instant cutoff, int batchSize) {
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff))
            .addValue("limit", batchSize);
        return jdbc.queryForList(
            "SELECT id FROM process_submission " +
            "WHERE status IN ('APPROVED', 'REJECTED') " +
            "  AND submitted_at < :cutoff " +
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
