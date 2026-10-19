package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-12 (F-03) на живом PostgreSQL: retention не застревает на FK
 * {@code process_instances.parent_activity_id → activities.id}.
 *
 * <p>Сценарий аудита ({@code docs/audit/ZorroBPM-full-audit-2026-10-19.md},
 * F-03): у любого инстанса с call activity есть дочерний инстанс, чья строка
 * ссылается на родительскую activity-строку. FK объявлен БЕЗ ON DELETE, а
 * {@code RetentionBatchProcessor.deleteRowsForInstances} удаляет activities
 * РАНЬШЕ process_instances → DELETE падает на FK, вся пачка откатывается, и
 * следующий прогон упирается в те же строки. Retention не прогрессирует
 * вообще — БД растёт.
 *
 * <p>Тест строит ровно эту топологию (родитель + call activity + дочерний
 * инстанс, оба терминальны и старше TTL) и проверяет, что батч УХОДИТ, а
 * повторный проход не застревает на тех же строках.
 */
@Tag("pg")
class Audit12RetentionCallActivityPgIT extends PostgresIT {

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

        // Определение с TTL=1 день: retention чистит по нему (WO-AUDIT-12: «TTL > 0»).
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at, history_time_to_live_days) " +
            "VALUES (?, 'audit12-retention', 1, 'Audit12 Retention', ?, ?, 1)",
            sharedPdId, UUID.randomUUID().toString(), ago(3 * 86400L));
    }

    /**
     * Родитель с call activity + дочерний инстанс, оба терминальны и старше TTL.
     *
     * @return id дочернего инстанса
     */
    private UUID seedParentWithCallActivityChild() {
        UUID parentPi = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            parentPi, sharedPdId, ago(3 * 86400L), ago(2 * 86400L));

        UUID parentToken = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", parentToken);
        // Родительская activity-строка call activity — на неё ссылается ребёнок.
        UUID callActivityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'call', ?, ?, 'CALL_ACTIVITY', 'COMPLETED', ?)",
            callActivityId, parentPi, ago(3 * 86400L), ago(2 * 86400L), parentToken);

        // Дочерний инстанс: parent_activity_id → родительская activity-строка.
        UUID childPi = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled, parent_activity_id) " +
            "VALUES (?, ?, ?, ?, false, ?)",
            childPi, sharedPdId, ago(3 * 86400L), ago(2 * 86400L), callActivityId);

        UUID childToken = UUID.randomUUID();
        jdbc.update("INSERT INTO tokens (id) VALUES (?)", childToken);
        jdbc.update(
            "INSERT INTO activities (id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) " +
            "VALUES (?, ?, 'childStart', ?, ?, 'START_EVENT', 'COMPLETED', ?)",
            UUID.randomUUID(), childPi, ago(3 * 86400L), ago(2 * 86400L), childToken);

        return childPi;
    }

    /**
     * F-03: батч retention с call activity УХОДИТ — FK не роняет всю пачку.
     *
     * <p>RED на коде до фикса: DELETE activities падает на
     * {@code fk_process_instances__parent_activity_id}, транзакция откатывается,
     * строки остаются — и следующий проход берёт те же строки снова.
     */
    @Test
    void callActivityBatch_isDeletedAndDoesNotStickOnTheNextPass() {
        UUID childPi = seedParentWithCallActivityChild();

        List<Integer> ttls = batchProcessor.loadDistinctTtls();
        assertThat(ttls).as("definition TTL is set").contains(1);

        RetentionBatchProcessor.ClaimedBatch first =
            batchProcessor.claimAndDeleteBatch(Instant.now(), 0, 100, ttls);

        assertThat(first.instanceIds()).as("both instances claimed in one batch").hasSize(2);
        assertThat(first.rowsDeleted()).as("rows deleted").isPositive();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, childPi))
            .as("child process instance is gone (PostgreSQL)").isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances", Integer.class))
            .as("no process instance rows left (PostgreSQL)").isEqualTo(0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities", Integer.class))
            .as("no activity rows left (PostgreSQL)").isEqualTo(0);

        // Повторный проход не застревает: eligible-строк больше нет.
        RetentionBatchProcessor.ClaimedBatch second =
            batchProcessor.claimAndDeleteBatch(Instant.now(), 0, 100, ttls);
        assertThat(second.instanceIds()).as("second pass has nothing to do").isEmpty();
        assertThat(second.rowsDeleted()).isZero();
    }

    /**
     * F-03, второй срез: дочерний инстанс, который НЕ попал в батч (другая
     * TTL/не терминален), не должен блокировать удаление родительских строк.
     *
     * <p>Топологический порядок «удалить ребёнка раньше родителя» здесь не
     * работает: ребёнок может быть не в этой пачке вообще. Связь
     * {@code parent_activity_id} обязана быть разорвана в коде перед удалением
     * activities.
     */
    @Test
    void parentBatch_survivesAChildThatIsNotInTheBatch() {
        UUID childPi = seedParentWithCallActivityChild();
        // Ребёнок ещё живой (не терминален) — в батч не попадает.
        jdbc.update(
            "UPDATE process_instances SET completed_at = NULL, cancelled = false WHERE id = ?",
            childPi);
        jdbc.update(
            "UPDATE activities SET completed_at = NULL, status = 'CREATED' "
                + "WHERE process_instance_id = ?", childPi);

        List<Integer> ttls = batchProcessor.loadDistinctTtls();
        RetentionBatchProcessor.ClaimedBatch first =
            batchProcessor.claimAndDeleteBatch(Instant.now(), 0, 100, ttls);

        assertThat(first.instanceIds()).as("only the parent is eligible").hasSize(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Integer.class, childPi))
            .as("the still-running child survives").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE bpmn_element_id = 'call'", Integer.class))
            .as("the parent's call activity row is gone (PostgreSQL)").isEqualTo(0);
    }
}
