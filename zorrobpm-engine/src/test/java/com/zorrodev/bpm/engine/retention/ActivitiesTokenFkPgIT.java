package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-OPS-12 (D-1): FK {@code fk_activities__token} (changeset 20260918-112).
 *
 * <p>До changeset activities.token (NOT NULL с 20260316-013) никак не был связан с
 * tokens.id — обход retention оставлял сирот молча. Политика — осознанно «только через
 * retention», БЕЗ DB-CASCADE: RESTRICT-евристика (дефолт) совместима с порядком
 * {@link RetentionBatchProcessor#deleteInstances} (activities раньше токенов) и превращает
 * будущий обход в громкую FK-ошибку.
 *
 * <p>G-N: changeset применяется самим Liquibase при старте контекста (не ручным SQL в
 * теле теста) — тест {@link #fkConstraintExists_blocksOrphanInsert} сначала проверяет
 * наличие constraint в information_schema, т.е. доказывает сам артефакт.
 */
public class ActivitiesTokenFkPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;

    private UUID sharedPdId;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE process_instances, activities, tokens, variables, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways, process_definitions RESTART IDENTITY CASCADE");

        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'ops12-fk-test', 1, 'OPS-12 FK Test', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), ago(200));
    }

    private UUID newInstance() {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));
        return piId;
    }

    private UUID newToken() {
        UUID tokenId = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", tokenId);
        return tokenId;
    }

    private void newActivity(UUID actId, UUID piId, UUID tokenId) {
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'startEvent', ?, ?, 'START_EVENT', 'COMPLETED', ?)",
            actId, piId, ago(90), ago(80), tokenId);
    }

    // ==================== Критерий 1: retention удаляет всё, сирот нет ====================

    @Test
    void retentionDeletesInstanceWithToken_noOrphans() {
        UUID piId = newInstance();
        UUID tokenId = newToken();
        newActivity(UUID.randomUUID(), piId, tokenId);

        // Прод-путь целиком: eligibility + batch delete реальной транзакцией.
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);
        batchProcessor.deleteInstances(eligible);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?", Integer.class, piId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM tokens WHERE id = ?", Integer.class, tokenId)).isEqualTo(0);
    }

    // ==================== Критерий 2: FK стоит, сирота не пишется ====================

    @Test
    void fkConstraintExists_blocksOrphanInsert() {
        // G-N: сначала доказываем сам артефакт — constraint создан changeset-112
        // при старте контекста (LOWER — регистр information_schema различается PG/H2).
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints " +
            "WHERE LOWER(table_name) = 'activities' AND LOWER(constraint_name) = 'fk_activities__token' " +
            "AND constraint_type = 'FOREIGN KEY'", Integer.class)).isEqualTo(1);

        // Непустая таблица + FK: валидная строка пишется без ошибок (миграция
        // на заселённой таблице не падает — критерий 2 WO).
        UUID piId = newInstance();
        UUID tokenId = newToken();
        UUID actId = UUID.randomUUID();
        newActivity(actId, piId, tokenId);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE id = ?", Integer.class, actId)).isEqualTo(1);

        // А сирота — нет: токена с таким id в tokens нет → громкая FK-ошибка,
        // а не тихая запись (POF-мутация — убрать addForeignKeyConstraint из
        // changeset — делает этот assert RED: вставка проходит).
        UUID orphanToken = UUID.randomUUID();
        assertThatThrownBy(() -> newActivity(UUID.randomUUID(), piId, orphanToken))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== RESTRICT, не CASCADE ====================

    @Test
    void tokenDeleteBlockedWhileReferenced_restrictNotCascade() {
        UUID piId = newInstance();
        UUID tokenId = newToken();
        newActivity(UUID.randomUUID(), piId, tokenId);

        // Удаление токена, на который ссылается живая activity, обязано падать —
        // CASCADE снёс бы ссылку молча (политика WO: удаление только через retention,
        // где activities уходят раньше токенов).
        assertThatThrownBy(() -> jdbc.update("DELETE FROM tokens WHERE id = ?", tokenId))
            .isInstanceOf(DataIntegrityViolationException.class);
        // И ничего не снесено вдогонку: activity жива.
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE token = ?", Integer.class, tokenId)).isEqualTo(1);
    }

    // ==================== HALT-предикат пропускает здоровые данные ====================

    @Test
    void orphanCheckQuery_noOrphansOnHealthyData() {
        // Тот же запрос, что sqlCheck-preCondition changeset-112: на здоровых данных
        // обязан вернуть 0 (иначе деплой остановится HALT — см. comment changeset).
        UUID piId = newInstance();
        newActivity(UUID.randomUUID(), piId, newToken());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities a LEFT JOIN tokens t ON t.id = a.token " +
            "WHERE a.token IS NOT NULL AND t.id IS NULL", Integer.class)).isEqualTo(0);
    }
}
