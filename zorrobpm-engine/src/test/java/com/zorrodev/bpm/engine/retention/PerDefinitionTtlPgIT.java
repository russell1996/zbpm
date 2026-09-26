package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-17: per-definition TTL — реальный PostgreSQL, реальный
 * {@code RetentionBatchProcessor.findEligibleInstances(now, fallbackDays, batch)}.
 *
 * <p>Три определения: TTL 7, TTL 30, NULL (наследовать глобальный). Старый
 * 2-arg метод не тронут — его PgIT'ы ({@code RetentionBatchProcessorPgIT})
 * доказывают обратную совместимость без единого касания.
 */
public class PerDefinitionTtlPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;

    private UUID def7;
    private UUID def30;
    private UUID defNull;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE process_instances, activities, tokens, variables, variable_history, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways, process_definitions RESTART IDENTITY CASCADE");

        def7 = newDefinition("ttl-7", 7);
        def30 = newDefinition("ttl-30", 30);
        defNull = newDefinition("ttl-null", null);
    }

    private UUID newDefinition(String code, Integer ttlDays) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at, history_time_to_live_days) " +
            "VALUES (?, ?, 1, ?, ?, ?, ?)",
            id, code + "-" + UUID.randomUUID(), "TTL probe " + code, UUID.randomUUID().toString(),
            ago(200), ttlDays);
        return id;
    }

    private UUID terminalInstance(UUID definitionId, long completedAgoSeconds) {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, definitionId, ago(completedAgoSeconds + 50), ago(completedAgoSeconds));
        return piId;
    }

    @Test
    void ownTtlWinsOverFallback() {
        // Оба завершены 10 дней назад; фолбэк 90 (не перекрывает ни один собственный TTL).
        UUID pi7 = terminalInstance(def7, 10 * 86400L);
        UUID pi30 = terminalInstance(def30, 10 * 86400L);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 90, 100);

        // 10 > 7 → чистится по своему TTL; 10 < 30 → не чистится (фолбэк 90 тоже не достаёт).
        assertThat(eligible).contains(pi7);
        assertThat(eligible).doesNotContain(pi30);
    }

    @Test
    void nullDefinition_inheritsFallbackWhenSet() {
        UUID piNull = terminalInstance(defNull, 100 * 86400L);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 30, 100);

        // NULL = наследовать глобальный (§4 WO): 100 > 30 → чистится.
        assertThat(eligible).contains(piNull);
    }

    @Test
    void nullDefinition_neverCleanedWhenNoFallback() {
        UUID piNull = terminalInstance(defNull, 100 * 86400L);
        UUID pi7 = terminalInstance(def7, 10 * 86400L);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 0, 100);

        // Глобальный выключен: NULL-определение не чистится никогда (дефолт «ничего не теряем»);
        // собственный TTL при этом работает независимо от глобального.
        assertThat(eligible).doesNotContain(piNull);
        assertThat(eligible).contains(pi7);
    }

    @Test
    void notOldEnoughPerOwnTtl_notEligible() {
        UUID young = terminalInstance(def7, 3 * 86400L);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 90, 100);

        assertThat(eligible).doesNotContain(young);
    }

    @Test
    void eligibleByOwnTtl_stillCascadesOnDelete() {
        UUID pi7 = terminalInstance(def7, 10 * 86400L);

        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 90, 100);
        assertThat(eligible).contains(pi7);

        int deleted = batchProcessor.deleteInstances(eligible);
        assertThat(deleted).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, pi7)).isEqualTo(0);
    }
}
