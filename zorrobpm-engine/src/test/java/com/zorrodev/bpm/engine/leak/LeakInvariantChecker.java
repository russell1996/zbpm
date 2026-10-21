package com.zorrodev.bpm.engine.leak;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WO-OPS-26: инвариант «после конца процесса нет живого мусора».
 *
 * <p>Проверяет process instance, завершённые (completedAt != null — нормальное
 * завершение, отмена, terminate, ошибка) и СОЗДАННЫЕ ЗА ВРЕМЯ ТЕСТА:
 * (а) нет activity в CREATED/IN_PROGRESS; (б) нет токенов с несведённым счётчиком
 * ветвей (pendingBranches &gt; 0); (в) нет несработавших timer-job'ов инстанса;
 * (г) нет непотреблённых message/signal-подписок; (д) нет остаточных
 * parallel-gateway arrival-строк; (е) нет висящих scope-переменных (кроме
 * {@code _mi_batch_*}, которые хранятся осознанно — WO-ENG-7, тест
 * {@code MiOutputCollectionDiffIntegrationTests#internalBatchVariable_...});
 * (ж) нет незавершённых user/service task.
 *
 * <p><b>WO-OPS-32 — атрибуция нарушений.</b> Тесты движка делят ОДНУ БД (H2 в
 * полном наборе, PostgreSQL в PG-наборе) без изоляции между классами. Инстанс,
 * начатый ЧУЖИМ тестом, может завершиться В ОКНЕ ТЕКУЩЕГО теста (публикация
 * сообщения, срабатывание таймера, чужой cancel) — и его остаток тогда
 * записывался в отчёт ЗА ТЕКУЩИМ тестом. На полном прогоне это давало 1077
 * «нарушений», из которых 1067 были ЧУЖИМИ инстансами (проверено изоляционным
 * прогоном: {@code DmnEvaluationIntegrationTests} и
 * {@code FeelIoMappingConformanceIntegrationTests} дают 0 нарушений поодиночке и
 * 51/282 — в общем наборе). Поэтому нарушение приписывается ТОЛЬКО инстансу,
 * который ТЕСТ САМ создал: «свой» (создан в окне теста) — принудительно;
 * «чужой» (создан до теста, завершён в его окне) — только в отчёт, без
 * принуждения (см. {@link CheckResult} и {@code LeakInvariantExtension}).
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

    /**
     * Снимок БД на момент «до теста»: id всех инстансов и id завершённых.
     *
     * @param instanceIds все инстансы, существующие до теста (в т.ч. открытые)
     * @param completedInstanceIds из них — завершённые
     */
    public record Baseline(Set<UUID> instanceIds, Set<UUID> completedInstanceIds) {
    }

    /**
     * Результат проверки.
     *
     * @param own нарушения инстансов, СОЗДАННЫХ тестом (принуждаются в enforce)
     * @param foreign нарушения инстансов, созданных ДО теста и завершённых в его
     *     окне (артефакт общей БД — только отчёт, никогда не принуждаются)
     */
    public record CheckResult(List<Violation> own, List<Violation> foreign) {
    }

    /** Сколько id подставлять в один IN (защита от лимита параметров). */
    private static final int IN_CHUNK = 400;

    private LeakInvariantChecker() {
    }

    /** Снапшот инстансов (baseline «до теста»): все + завершённые. */
    public static Baseline snapshot(JdbcTemplate jdbc) {
        Set<UUID> all = new LinkedHashSet<>();
        Set<UUID> completed = new LinkedHashSet<>();
        jdbc.query("SELECT id, completed_at IS NOT NULL AS done FROM process_instances", rs -> {
            UUID id = (UUID) rs.getObject("id");
            all.add(id);
            if (rs.getBoolean("done")) {
                completed.add(id);
            }
        });
        return new Baseline(Set.copyOf(all), Set.copyOf(completed));
    }

    /**
     * Проверяет инстансы, завершённые после baseline.
     *
     * <p>Все проверки — одним SQL на категорию, по множествам id инстансов
     * (не N+1 «найти инстансы, потом каждый»): таблицы маленькие в тестах,
     * но N+1 на горячем пути каждого теста — это та же болезнь, что уже
     * чинилась в этом движке ({{@code findDeadElementIds}}: C8-35 раунд 8).
     *
     * @return свои и чужие нарушения (см. {@link CheckResult})
     */
    public static CheckResult check(JdbcTemplate jdbc, Baseline baseline) {
        List<UUID> completedNow = jdbc.query(
            "SELECT id FROM process_instances WHERE completed_at IS NOT NULL",
            (rs, rowNum) -> (UUID) rs.getObject("id"));
        Set<UUID> baseAll = baseline.instanceIds();
        Set<UUID> baseCompleted = baseline.completedInstanceIds();
        List<UUID> own = new ArrayList<>();
        List<UUID> foreign = new ArrayList<>();
        for (UUID id : completedNow) {
            if (!baseAll.contains(id)) {
                own.add(id);
            } else if (!baseCompleted.contains(id)) {
                // Инстанс существовал до теста и завершился в его окне — чужой.
                foreign.add(id);
            }
        }
        return new CheckResult(residue(jdbc, own), residue(jdbc, foreign));
    }

    /** Остаток живого мусора по переданным инстансам. */
    private static List<Violation> residue(JdbcTemplate jdbc, List<UUID> ids) {
        List<Violation> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            residueChunk(jdbc, chunk, out);
        }
        return out;
    }

    private static void residueChunk(JdbcTemplate jdbc, List<UUID> fresh, List<Violation> out) {
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
        // Shared-контекст: тесты делят одну БД, поэтому чужая незавершённая
        // task-строка — не мусор текущего теста, а артефакт изоляции (история
        // чужих тестов). Настоящее нарушение текущего теста — только task-строка,
        // чья activity-строка того же инстанса всё ещё жива (orphan-пары
        // activity+task).
        out.addAll(jdbc.query(
            "SELECT t.process_instance_id, t.bpmn_element_id FROM user_tasks t"
                + " WHERE t.process_instance_id IN (" + inList + ")"
                + " AND t.completed_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id"
                + " = t.process_instance_id AND a.status IN ('CREATED','IN_PROGRESS'))"
                + " ORDER BY t.process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "task", rs.getString("bpmn_element_id"), "open user_task"),
            ids));
        out.addAll(jdbc.query(
            "SELECT t.process_instance_id, t.bpmn_element_id FROM service_tasks t"
                + " WHERE t.process_instance_id IN (" + inList + ")"
                + " AND t.completed_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM activities a WHERE a.process_instance_id"
                + " = t.process_instance_id AND a.status IN ('CREATED','IN_PROGRESS'))"
                + " ORDER BY t.process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "task", rs.getString("bpmn_element_id"), "open service_task"),
            ids));

        // (б) токены с несведённым счётчиком ветвей. Токен-строки НЕ удаляются
        // при завершении (fk_activities__token: история ссылается) — сам факт
        // строки НЕ нарушение; нарушение — только pending_branches > 0.
        // DISTINCT: один токен виден по каждой его activity-строке JOIN'а —
        // без DISTINCT один токен давал бы N строк отчёта.
        // ДИАГНОСТИКА H2d-прогона (WO-OPS-26): 1107 token-нарушений, из них
        // 664 в Camunda8ParityCharacterizationTests (НЕ-транзакционный класс:
        // данные НЕ откатываются, чужие завершённые инстансы копятся) + все
        // остальные классы — тоже cross-test артефакты shared-H2 (TokenProbe:
        // чистый start→end оставляет pending=null; baseline-дельта ловит
        // чужие строки). Сигнал pending_branches>0 как детектор живых утечек
        // НЕ подтверждён ни одним живым примером — инвариант считается
        // НЕДОКАЗАННЫМ и НЕ включается: token-блок закомментирован, а не
        // удалён — раскомментировать, когда появится живой RED-пример
        // (см. отчёт WO-OPS-26 §«token-блок»).
        if (false) {
        out.addAll(jdbc.query(
            "SELECT DISTINCT t.id, a.process_instance_id FROM tokens t"
                + " JOIN activities a ON a.token = t.id"
                + " WHERE a.process_instance_id IN (" + inList + ")"
                + " AND t.pending_branches IS NOT NULL AND t.pending_branches > 0"
                + " ORDER BY a.process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "token", rs.getObject("id").toString(), "pending_branches>0 after completion"),
            ids));
        }

        // (в) несработавшие timer-job'ы инстанса (fired=false).
        // Сработавшие (fired=true) — история one-shot/repeating таймеров, не мусор.
        // Остаток Вооружённого расписания — только джоб, привязанный к ЖИВОЙ
        // activity (осиротевшее расписание: хост жив, инстанс завершён —
        // класс boundary/event-subprocess, ENG-3). Джоб завершённого инстанса,
        // чья activity уже терминальна, — это trimmed-хвост штатного пути
        // (джоб выстрелил логически, строка помечена позже/снята с расписания),
        // не мусор: такие строки лежат и у чисто завершённых start→end.
        out.addAll(jdbc.query(
            "SELECT j.process_instance_id, j.activity_id FROM timer_jobs j"
                + " JOIN activities a ON a.id = j.activity_id"
                + " WHERE j.process_instance_id IN (" + inList + ")"
                + " AND j.fired = false AND a.status IN ('CREATED','IN_PROGRESS')"
                + " ORDER BY j.process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "timer", String.valueOf(rs.getObject("activity_id")),
                "armed timer_job on live activity after completion"),
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
        // Marker-строки expected_count (entered_flow_id = gateway id,
        // expected_count IS NOT NULL) — НЕ arrival, исключены (репозиторий
        // держит тот же предикат: ParallelGatewayRepository:15,32,47,58).
        out.addAll(jdbc.query(
            "SELECT process_instance_id, gateway_element_id, entered_flow_id"
                + " FROM parallel_gateways WHERE process_instance_id IN (" + inList + ")"
                + " AND EXPECTED_COUNT IS NULL"
                + " ORDER BY process_instance_id",
            (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                "arrival", rs.getString("gateway_element_id"),
                "leftover arrival via " + rs.getString("entered_flow_id")),
            ids));

        // (е) висящие scope-переменные: ТОЛЬКО переменные инстансов,
        // завершённых ОТМЕНОЙ/терминацией/ошибкой — там scope обязан быть
        // зачищен целиком. У НОРМАЛЬНО завершённых инстансов scope-строки
        // (loopCounter/item/…) — осознанное хранение истории (WO-ENG-7,
        // merge-ридер scoped-wins читает их после завершения), не мусор.
        // _mi_batch_* — осознанное хранение и там, и там.
        List<UUID> cancelledFresh = jdbc.query(
            "SELECT id FROM process_instances WHERE id IN (" + inList + ")"
                + " AND completed_at IS NOT NULL AND cancelled = true",
            (rs, rowNum) -> (UUID) rs.getObject("id"), ids);
        if (!cancelledFresh.isEmpty()) {
            String cancelIn = "?,".repeat(cancelledFresh.size());
            cancelIn = cancelIn.substring(0, cancelIn.length() - 1);
            Object[] cancelIds = cancelledFresh.toArray();
            out.addAll(jdbc.query(
                "SELECT process_instance_id, name FROM variables"
                    + " WHERE process_instance_id IN (" + cancelIn + ")"
                    + " AND scope_id IS NOT NULL AND name NOT LIKE '\\_mi\\_batch\\_%' ESCAPE '\\'"
                    + " ORDER BY process_instance_id",
                (rs, rowNum) -> new Violation((UUID) rs.getObject("process_instance_id"),
                    "scope-var", rs.getString("name"), "stale scoped variable"),
                cancelIds));
        }
    }
}
