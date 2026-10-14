package com.zorrodev.bpm.engine.leak;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WO-OPS-26: инвариант «после конца процесса нет живого мусора».
 *
 * <p>Проверяет все process instance, завершённые (completedAt != null — нормальное
 * завершение, отмена, terminate, ошибка) ПОСЛЕ переданного baseline-снапшота:
 * (а) нет activity в CREATED/IN_PROGRESS; (б) нет токенов с несведённым счётчиком
 * ветвей (pendingBranches &gt; 0); (в) нет несработавших timer-job'ов инстанса;
 * (г) нет непотреблённых message/signal-подписок; (д) нет остаточных
 * parallel-gateway arrival-строк; (е) нет висящих scope-переменных (кроме
 * {@code _mi_batch_*}, которые хранятся осознанно — WO-ENG-7, тест
 * {@code MiOutputCollectionDiffIntegrationTests#internalBatchVariable_...});
 * (ж) нет незавершённых user/service task.
 *
 * <p>Инстансы, НЕ завершённые к моменту проверки (тест намеренно оставляет
 * процесс), не проверяются.
 */
public final class LeakInvariantChecker {

    /** Одно нарушение инварианта. */
    public record Violation(UUID instanceId, String type, String elementId, String detail) {
        @Override
        public String toString() {
            return "instance=" + instanceId + " type=" + type + " element=" + elementId + " " + detail;
        }
    }

    private LeakInvariantChecker() {
    }

    /** Снапшот id завершённых инстансов (baseline «до теста»). */
    public static Set<UUID> snapshotCompletedInstanceIds(JdbcTemplate jdbc) {
        return Set.copyOf(jdbc.query(
            "SELECT id FROM process_instances WHERE completed_at IS NOT NULL",
            (rs, rowNum) -> (UUID) rs.getObject("id")));
    }

    /**
     * Проверяет инстансы, завершённые после baseline.
     *
     * <p>Все проверки — одним SQL на категорию, по множествам id инстансов
     * (не N+1 «найти инстансы, потом каждый»): таблицы маленькие в тестах,
     * но N+1 на горячем пути каждого теста — это та же болезнь, что уже
     * чинилась в этом движке ({{@code findDeadElementIds}}: C8-35 раунд 8).
     *
     * @return список нарушений (пусто = чисто)
     */
    public static List<Violation> check(JdbcTemplate jdbc, Set<UUID> baselineCompleted) {
        List<UUID> fresh = jdbc.query(
            "SELECT id FROM process_instances WHERE completed_at IS NOT NULL",
            (rs, rowNum) -> (UUID) rs.getObject("id")).stream()
            .filter(id -> !baselineCompleted.contains(id))
            .toList();
        if (fresh.isEmpty()) {
            return List.of();
        }
        List<Violation> out = new ArrayList<>();
        String inList = "?,".repeat(fresh.size());
        inList = inList.substring(0, inList.length() - 1);
        Object[] ids = fresh.toArray();

        // (а) живые activity: CREATED/IN_PROGRESS в завершённом инстансе.
        // ERROR — терминальный статус (история, не мусор): исключён осознанно.
        out.addAll(jdbc.query(
            "SELECT process_instance_id, bpmn_element_id, status FROM activities"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND status IN ('CREATED','IN_PROGRESS') ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "activity", rs.getString("bpmn_element_id"),
                "live activity, status=" + rs.getString("status")),
            ids));

        // (ж) висящие user/service task (completed_at IS NULL).
        out.addAll(jdbc.query(
            "SELECT process_instance_id, bpmn_element_id FROM user_tasks"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND completed_at IS NULL ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "task", rs.getString("bpmn_element_id"), "open user_task"),
            ids));
        out.addAll(jdbc.query(
            "SELECT process_instance_id, bpmn_element_id FROM service_tasks"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND completed_at IS NULL ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "task", rs.getString("bpmn_element_id"), "open service_task"),
            ids));

        // (б) токены с несведённым счётчиком ветвей. Токен-строки НЕ удаляются
        // при завершении (fk_activities__token: история ссылается) — сам факт
        // строки НЕ нарушение; нарушение — только pending_branches > 0.
        // DISTINCT: один токен виден по каждой его activity-строке JOIN'а —
        // без DISTINCT один токен давал бы N строк отчёта.
        out.addAll(jdbc.query(
            "SELECT DISTINCT t.id, a.process_instance_id FROM tokens t"
                + " JOIN activities a ON a.token = t.id"
                + " WHERE a.process_instance_id IN (" + inList + ")"
                + " AND t.pending_branches IS NOT NULL AND t.pending_branches > 0"
                + " ORDER BY a.process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "token", rs.getObject("id").toString(), "pending_branches>0 after completion"),
            ids));

        // (в) несработавшие timer-job'ы инстанса (fired=false).
        // Сработавшие (fired=true) — история one-shot/repeating таймеров, не мусор.
        out.addAll(jdbc.query(
            "SELECT process_instance_id, activity_id FROM timer_jobs"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND fired = false ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "timer", String.valueOf(rs.getObject("activity_id")),
                "unfired timer_job left behind"),
            ids));

        // (г) непотреблённые подписки инстанса (только instance-scoped таблицы;
        // message_start/signal_start — подписки ДЕПЛОЯ, не инстанса).
        out.addAll(jdbc.query(
            "SELECT process_instance_id, message_name FROM message_subscriptions"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND consumed = false ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "subscription", rs.getString("message_name"),
                "unconsumed message_subscription"),
            ids));
        out.addAll(jdbc.query(
            "SELECT process_instance_id, signal_name FROM signal_subscriptions"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND consumed = false ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "subscription", rs.getString("signal_name"),
                "unconsumed signal_subscription"),
            ids));

        // (д) остаточные parallel-gateway arrival-строки (join обязан чистить).
        out.addAll(jdbc.query(
            "SELECT process_instance_id, gateway_element_id, entered_flow_id"
                + " FROM parallel_gateways WHERE process_instance_id IN (" + inList + ")"
                + " ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "arrival", rs.getString("gateway_element_id"),
                "leftover arrival via " + rs.getString("entered_flow_id")),
            ids));

        // (е) висящие scope-переменные. _mi_batch_* — осознанное хранение
        // (WO-ENG-7, MiOutputCollectionDiff#internalBatchVariable_...): не нарушение.
        out.addAll(jdbc.query(
            "SELECT process_instance_id, name FROM variables"
                + " WHERE process_instance_id IN (" + inList + ")"
                + " AND scope_id IS NOT NULL AND name NOT LIKE '\\_mi\\_batch\\_%' ESCAPE '\\'"
                + " ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "scope-var", rs.getString("name"), "stale scoped variable"),
            ids));

        return List.copyOf(out);
    }
}
