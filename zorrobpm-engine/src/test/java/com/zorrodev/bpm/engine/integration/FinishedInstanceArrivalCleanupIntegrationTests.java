package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-48: bookkeeping-строки join-ов ({@code parallel_gateways}) — транзиентное
 * состояние ОДНОГО инстанса («the rows are then deleted (consumed)», javadoc
 * {@code ParallelGatewayEntity}). Инстанс, ушедший в терминальное состояние
 * (completed/cancelled), не должен оставлять НИ ОДНОЙ своей строки: ни
 * arrival-строки живых join-ов, ни marker-строки ({@code expected_count} set:
 * MI-batch, ad-hoc {@code joinKey}, inclusive-маркеры).
 *
 * <p>Данные WO-OPS-26 §7: 139 «настоящих» остаточных arrival-строк (из них 136 —
 * маркеро-подобные {@code element==via} от MI/ad-hoc bookkeeping), в H2g 294
 * {@code element==via} вида {@code <activityUuid>::<batchUuid>} — это
 * {@code AdHocJoin.joinKey}/{@code miId::batchUuid}. Читателей у строк
 * ТЕРМИНАЛЬНОГО инстанса в main нет (ElementSupport:2037-2062 — только живые
 * инстансы/активности), retention удаляет таблицу целиком
 * (RetentionBatchProcessor:234) — таблица транзиентная by design, и зачистка при
 * завершении/отмене инстанса возвращает ей это свойство.
 *
 * <p>Красный класс воспроизводится ЖИВО (probe, {@code target/leak-invariant-report.txt}):
 * ad-hoc сценарии C8-38 с парковкой соседнего join-а — 4 пути отмены scope
 * (error scope-walk / interrupting escalation / terminate in-scope / cancel-end).
 * Инстанс реально завершается (это уже утверждает
 * {@code AdhocScopeArrivalCleanupIntegrationTests}), а ad-hoc bookkeeping-строки
 * (arrival: {@code FlowNavigator:210}, marker: {@code AdHocSubProcessHandler:244})
 * остаются: C8-38-чистки адресуются по {@code inclusiveGatewayIdsInsideScope}
 * (bpmn-ids) и UUID-based {@code joinKey} ad-hoc-скоупа не видят, а done-хук
 * ad-hoc ({@code FlowNavigator.finishAdHocScope0}) на cancel-путях не вызывается.
 * MI-путь даёт mirror-случай: cancel посреди MI оставляет marker-строку
 * {@code miId::batchUuid} ({@code MultiInstanceExecutor:248}).
 *
 * <p>Проверки — ПОСЛЕ КОММИТА: терминальная зачистка (WO-C8-48) выполняется в фазе
 * коммита той же транзакции — ПОСЛЕ хвостовых перепроверок припаркованных join'ов,
 * которым arrival-строки нужны живыми (матрица C8-35, 241/241). Поэтому сценарии
 * этого класса НЕ оборачиваются в тестовую {@code @Transactional}: откат тестовой
 * транзакции не выполнил бы фазу коммита, и проверка доказывала бы поведение,
 * которого в прод-пути не существует. Каждый шаг идёт прод-транзакцией
 * ({@code RuntimeService}/операторская последовательность в TransactionTemplate),
 * наружу утверждается наблюдаемое состояние БД после коммита.
 *
 * <p>Счёт — JdbcTemplate-ом, не JPA: зачистка идёт bulk-запросом мимо
 * persistence-контекста (WO-C8-46, «COUNT по таблице — факт БД, не кэша»).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FinishedInstanceArrivalCleanupIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private ParallelGatewayRepository parallelGatewayRepository;
    @Autowired private com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations parallelGatewayDbOperations;
    @Autowired private DBService dbService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private UUID startFixture(String file) throws Exception {
        return startFixture(file, List.of());
    }

    private UUID startFixture(String file, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    private void complete(UUID pi, String elementId) {
        activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /** Факт БД, не кэша: COUNT всех строк инстанса (arrival + marker). */
    private long bookkeepingRows(UUID pi) {
        Long n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM parallel_gateways WHERE process_instance_id = ?",
            Long.class, pi);
        return n == null ? 0L : n;
    }

    private String bookkeepingDump(UUID pi) {
        return jdbcTemplate.query(
            "SELECT gateway_element_id, entered_flow_id, expected_count FROM parallel_gateways "
                + "WHERE process_instance_id = ? ORDER BY gateway_element_id, entered_flow_id",
            (rs, i) -> rs.getString(1) + " via " + rs.getString(2) + " expected=" + rs.getObject(3),
            pi).toString();
    }

    /** Операторская отмена — та же последовательность, что в RuntimeOperations, в одной tx. */
    private void operatorCancel(UUID pi) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });
    }

    private void assertTerminalWithNoBookkeeping(UUID pi, String scenario) {
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("[%s] instance reached a terminal state", scenario).isNotNull();
        assertThat(bookkeepingRows(pi))
            .as("[%s] a terminal instance leaves NO parallel_gateways rows; leftovers: %s",
                scenario, bookkeepingDump(pi))
            .isZero();
    }

    /**
     * Тот же ход, что {@code AdhocScopeArrivalCleanupIntegrationTests}: taskY паркует
     * joinMain → A паркует joinIn (B жив) → E бросает триггер (scope гасится
     * cancel-путём) → taskAfter завершает инстанс.
     */
    private void driveAdhocCancelScenario(String fixture) throws Exception {
        UUID pi = startFixture(fixture);
        complete(pi, "taskY");
        complete(pi, "A");
        complete(pi, "E");
        complete(pi, "taskAfter");
        assertTerminalWithNoBookkeeping(pi, fixture);
    }

    @Test
    void adhocErrorScopeWalk_instanceCompletes_withoutBookkeeping() throws Exception {
        driveAdhocCancelScenario("test-c838-adhoc-errwalk-holds-neighbour-join.bpmn");
    }

    @Test
    void adhocInterruptingEscalation_instanceCompletes_withoutBookkeeping() throws Exception {
        driveAdhocCancelScenario("test-c838-adhoc-escalate-holds-neighbour-join.bpmn");
    }

    @Test
    void adhocTerminateInScope_instanceCompletes_withoutBookkeeping() throws Exception {
        driveAdhocCancelScenario("test-c838-adhoc-terminate-holds-neighbour-join.bpmn");
    }

    @Test
    void adhocCancelEnd_instanceCompletes_withoutBookkeeping() throws Exception {
        driveAdhocCancelScenario("test-c838-adhoc-cancelend-holds-neighbour-join.bpmn");
    }

    /**
     * Операторская отмена посреди живого MI-подпроцесса (тот же порядок вызовов,
     * что {@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}):
     * marker-строка MI ({@code miId::batchUuid}, {@code expected_count} set) и
     * arrival-строка уже завершившейся копии обязаны исчезнуть.
     */
    @Test
    void operatorCancel_miSubprocessMidFlight_withoutBookkeeping() throws Exception {
        UUID pi = startFixture("test-c840-mi-sub-minimal.bpmn");
        assertThat(countOf(pi, "utProbe", ActivityStatus.CREATED))
            .as("premise: 3 live MI instances before the cancel").isEqualTo(3L);
        complete(pi, "utProbe");
        assertThat(bookkeepingRows(pi))
            .as("premise: MI bookkeeping rows exist before the cancel; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of MI subprocess mid-flight");
    }

    /**
     * Операторская отмена ВЛОЖЕННОГО случая: внешний MI-подпроцесс уже прошёл
     * вложенный подпроцесс и держит живой ВНУТРЕННИЙ MI ({@code miTaskA} ×3 внутри
     * двух внешних инстансов) — bookkeeping-строки обоих уровней обязаны исчезнуть.
     */
    @Test
    void operatorCancel_nestedMiSubprocessMidFlight_withoutBookkeeping() throws Exception {
        ProcessVariable routeToMi = new ProcessVariable();
        routeToMi.setName("routeToMi");
        routeToMi.setType(ProcessVariableType.BOOLEAN);
        routeToMi.setValue("true");
        UUID pi = startFixture("test-c840-mi-sub-nested.bpmn", List.of(
            jsonVar("items", "[{\"who\":\"a\",\"members\":[\"a1\",\"a2\"]},{\"who\":\"b\",\"members\":[\"b1\"]}]"),
            routeToMi));

        complete(pi, "utInner");
        complete(pi, "utInner");
        assertThat(countOf(pi, "miTaskA", ActivityStatus.CREATED))
            .as("premise: inner MI instances are live inside both outer instances").isEqualTo(3L);
        assertThat(bookkeepingRows(pi))
            .as("premise: nested MI bookkeeping rows exist before the cancel; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of a nested MI subprocess mid-flight");
    }

    /**
     * Операторская отмена ad-hoc сцены с ДВУМЯ припаркованными join-ами:
     * arrival-строки joinMain/joinIn и ad-hoc bookkeeping (joinKey) обязаны
     * исчезнуть вместе с терминальным состоянием.
     */
    @Test
    void operatorCancel_adhocWithParkedJoins_withoutBookkeeping() throws Exception {
        UUID pi = startFixture("test-c838-adhoc-errwalk-holds-neighbour-join.bpmn");
        complete(pi, "taskY");
        complete(pi, "A");
        assertThat(bookkeepingRows(pi))
            .as("premise: parked joins hold arrival rows; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of an ad-hoc scope with parked joins");
    }

    /** Регресс нормального пути: MI завершается сам — строк не остаётся. */
    @Test
    void miSubprocessNormalCompletion_withoutBookkeeping() throws Exception {
        UUID pi = startFixture("test-c840-mi-sub-minimal.bpmn");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        assertTerminalWithNoBookkeeping(pi, "MI subprocess normal completion");
    }

    /**
     * Регресс нормального пути, SEQUENTIAL MI: инстансы идут по одному (next после
     * previous), marker-строка батча живёт всю последовательность и обязана исчезнуть
     * вместе с терминальным состоянием.
     */
    @Test
    void miSubprocessSequentialNormalCompletion_withoutBookkeeping() throws Exception {
        UUID pi = startFixture("test-c840-mi-sub-sequential.bpmn");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        assertTerminalWithNoBookkeeping(pi, "sequential MI subprocess normal completion");
    }

    /**
     * ВЛОЖЕННЫЙ случай: внешний MI-подпроцесс → обычный вложенный подпроцесс
     * ({@code utInner}) и внутренний MI ({@code miTaskA}, 3 инстанса внутри 2 внешних).
     * Bookkeeping-строки обоих уровней (внешний batch + внутренний batch) обязаны
     * исчезнуть при завершении инстанса. Тот же ход, что
     * {@code MiSubProcessIntegrationTests#nested_plainSubSeesOuterLoopVar_innerMiTaskSeesBoth}.
     */
    @Test
    void nestedMiSubprocess_normalCompletion_withoutBookkeeping() throws Exception {
        ProcessVariable routeToMi = new ProcessVariable();
        routeToMi.setName("routeToMi");
        routeToMi.setType(ProcessVariableType.BOOLEAN);
        routeToMi.setValue("true");
        UUID pi = startFixture("test-c840-mi-sub-nested.bpmn", List.of(
            jsonVar("items", "[{\"who\":\"a\",\"members\":[\"a1\",\"a2\"]},{\"who\":\"b\",\"members\":[\"b1\"]}]"),
            routeToMi));

        complete(pi, "utInner");
        complete(pi, "utInner");
        complete(pi, "miTaskA");
        complete(pi, "miTaskA");
        complete(pi, "miTaskA");
        assertTerminalWithNoBookkeeping(pi, "nested MI subprocess normal completion");
    }

    /**
     * Гонка по G-P: две ветки MI (N=2) завершаются ИЗ РАЗНЫХ потоков по общему
     * барьеру — ровно один терминальный переход и зачистка bookkeeping-строк под
     * instance-lock ({@code ElementSupport.lockInstanceFirst}) без потери
     * outputCollection. Не в {@code @Transactional}: каждый поток идёт своей
     * транзакцией через прод-путь {@code RuntimeService.completeUserTask}.
     */
    @Test
    void twoBranchesFinishConcurrently_singleTerminalCleanup() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c840-mi-sub-race.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(jsonVar("items", "[\"t0\",\"t1\"]")));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<UUID> taskIds = new TransactionTemplate(txManager).execute(s ->
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> "utProbe".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
                .map(ActivityEntity::getId)
                .toList());
        assertThat(taskIds).as("two parallel MI instances parked").hasSize(2);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskIds.size());
        for (UUID taskId : taskIds) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    runtimeService.completeUserTask(taskId, List.of());
                } catch (Throwable th) {
                    errors.add(th);
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("both concurrent completions finished").isTrue();
        assertThat(errors).as("no completion failed under the race: %s", errors).isEmpty();

        java.time.Instant completedAt = new TransactionTemplate(txManager).execute(s ->
            queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).as("instance completed after the race").isNotNull();
        ProcessVariableEntity results = new TransactionTemplate(txManager).execute(s ->
            variableRepository.findByNameAndProcessInstanceId("results", pi).orElseThrow());
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("outputCollection full and ordered by slot").containsExactly("t0", "t1");
        assertThat(bookkeepingRows(pi))
            .as("terminal cleanup survived the concurrent branch completion; leftovers: %s",
                bookkeepingDump(pi))
            .isZero();
    }

    private static ProcessVariable jsonVar(String name, String json) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(json);
        return v;
    }

    /**
     * Инвариант порядка на терминальном DELETE для строк, ЛЕЖАЩИХ В
     * PERSISTENCE-КОНТЕКСТЕ ещё не сброшенными: {@code save()} только планирует
     * INSERT, и терминальный DELETE одной транзакцией обязан снести и его — иначе
     * commit-флаш вставит строку уже ПОСЛЕ удаления (расхождение кэша и БД).
     * Проверка детерминирована, без гонок и sleep: сохраняем строку, СРАЗУ
     * удаляем (без промежуточных чтений таблицы, которые могли бы вызвать
     * авто-флаш), затем форсируем flush и считаем строки raw-JDBC.
     *
     * <p>Что доказано живьём: на стеке Hibernate ORM 7.2 потоки выполнения —
     * {@code select (merge-check) → insert (сброс pending-INSERT перед mutation) →
     * delete}, т.е. DELETE видит и pending-строку (SQL-лог прогона с
     * {@code logging.level.org.hibernate.SQL=DEBUG} в отчёте WO-C8-48). Это
     * свойство провайдера (Spring Data сам флашит только при
     * {@code flushAutomatically=true} — проверено байткодом
     * {@code JpaQueryExecution$ModifyingExecution}), поэтому тест — регресс-гвардия
     * инварианта «терминальный DELETE сносит и pending-строки», а не kill-мутация
     * флага (мутация «снять flushAutomatically» оставляет его зелёным — честно
     * зафиксировано в отчёте).
     */
    @Transactional
    @Test
    void bookkeepingDelete_cleansRowsPendingInPersistenceContext() {
        UUID pi = UUID.randomUUID();
        ParallelGatewayEntity pending = new ParallelGatewayEntity();
        pending.setId(UUID.randomUUID());
        pending.setProcessInstanceId(pi);
        pending.setGatewayElementId("joinFlushProbe");
        pending.setEnteredFlowId("joinFlushProbe");
        pending.setExpectedCount(2);
        pending.setCreatedAt(java.time.Instant.now());
        parallelGatewayRepository.save(pending);

        parallelGatewayDbOperations.clearInstanceBookkeeping(pi);
        parallelGatewayRepository.flush();
        assertThat(bookkeepingRows(pi))
            .as("терминальный DELETE обязан снести и pending-строку (INSERT до DELETE), "
                + "а не оставить её после commit-флаша (leftovers: %s)", bookkeepingDump(pi))
            .isZero();
    }
}
