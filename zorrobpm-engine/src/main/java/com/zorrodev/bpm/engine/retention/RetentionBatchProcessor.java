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

    @Transactional(readOnly = true)
    public List<UUID> findEligibleInstances(Instant cutoff, int batchSize) {
        String sql = "SELECT pi.id FROM process_instances pi " +
            "WHERE (pi.completed_at IS NOT NULL OR pi.cancelled = true) " +
            "AND pi.completed_at < :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id = pi.id AND a.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM user_tasks ut WHERE ut.process_instance_id = pi.id AND ut.completed_at IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM service_tasks st WHERE st.process_instance_id = pi.id AND st.completed_at IS NULL) " +
            "ORDER BY pi.completed_at ASC LIMIT :limit";
        return jdbc.queryForList(sql,
            new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", batchSize),
            UUID.class);
    }

    @Transactional
    public int deleteInstances(List<UUID> instanceIds) {
        if (instanceIds.isEmpty()) return 0;
        MapSqlParameterSource params = new MapSqlParameterSource("ids", instanceIds);

        int total = 0;
        total += jdbc.update("DELETE FROM timer_jobs WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM message_subscriptions WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM signal_subscriptions WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM parallel_gateways WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM incidents WHERE activity_id IN (SELECT id FROM activities WHERE process_instance_id IN (:ids))", params);
        total += jdbc.update("DELETE FROM service_tasks WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM user_tasks WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM variables WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM activities WHERE process_instance_id IN (:ids)", params);
        total += jdbc.update("DELETE FROM tokens WHERE id IN (SELECT DISTINCT token FROM activities WHERE process_instance_id IN (:ids))", params);
        total += jdbc.update("DELETE FROM process_instances WHERE id IN (:ids)", params);

        log.info("Retention: deleted {} rows for {} instances", total, instanceIds.size());
        return total;
    }
}
