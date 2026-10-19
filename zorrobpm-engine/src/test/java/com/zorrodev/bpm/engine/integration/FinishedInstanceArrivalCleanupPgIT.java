package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
 * WO-C8-48 на реальном PostgreSQL: bookkeeping-строки join-ов
 * ({@code parallel_gateways}) транзиентны — терминальный инстанс (completed ИЛИ
 * cancelled) не оставляет ни одной своей строки, включая marker-строки
 * ({@code expected_count} set) и строки, ещё лежащие в persistence-контексте.
 *
 * <p>H2-двойник — {@code FinishedInstanceArrivalCleanupIntegrationTests}; здесь
 * прод-путь гоняется через реальные транзакции (TransactionTemplate / сервисные
 * методы), а не тест-обёртку: bulk-DELETE виден только на закоммиченном
 * состоянии, а {@code expected_count INTEGER}/{@code created_at} и конкурентные
 * ветки проверяются на настоящем PostgreSQL (P-17/P-22, G-P).
 *
 * <p>Сценарии: MI parallel (3 копии), MI sequential, вложенный MI, операторская
 * отмена MI/вложенного MI/ad-hoc с паркованными join-ами, ad-hoc cancel-end по
 * шагам пользователя и гонка двух веток из РАЗНЫХ потоков (инстанс-лок
 * {@code ElementSupport.lockInstanceFirst}).
 */
public class FinishedInstanceArrivalCleanupPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private ParallelGatewayRepository parallelGatewayRepository;
    @Autowired private ParallelGatewayDbOperations parallelGatewayDbOperations;
    @Autowired private DBService dbService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID startFixture(String file, List<ProcessVariable> variables) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + file));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(variables);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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

    /** Факт БД (не кэша): COUNT всех строк инстанса (arrival + marker). */
    private long bookkeepingRows(UUID pi) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM parallel_gateways WHERE process_instance_id = ?",
            Long.class, pi);
        return n == null ? 0L : n;
    }

    private String bookkeepingDump(UUID pi) {
        return jdbc.query(
            "SELECT gateway_element_id, entered_flow_id, expected_count FROM parallel_gateways "
                + "WHERE process_instance_id = ? ORDER BY gateway_element_id, entered_flow_id",
            (rs, i) -> rs.getString(1) + " via " + rs.getString(2) + " expected=" + rs.getObject(3),
            pi).toString();
    }

    private void assertTerminalWithNoBookkeeping(UUID pi, String scenario) {
        java.time.Instant completedAt = tx.execute(s ->
            queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt)
            .as("[%s] instance reached a terminal state", scenario).isNotNull();
        assertThat(bookkeepingRows(pi))
            .as("[%s] a terminal instance leaves NO parallel_gateways rows; leftovers: %s",
                scenario, bookkeepingDump(pi))
            .isZero();
    }

    /** Операторская отмена — та же последовательность, что в RuntimeOperations. */
    private void operatorCancel(UUID pi) {
        tx.executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });
    }

    private static ProcessVariable jsonVar(String name, String json) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(json);
        return v;
    }

    @Test
    void miSubprocessNormalCompletion_withoutBookkeeping() {
        UUID pi = startFixture("test-c840-mi-sub-minimal.bpmn", List.of());
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        assertTerminalWithNoBookkeeping(pi, "MI subprocess normal completion (pg)");
    }

    @Test
    void miSubprocessSequentialNormalCompletion_withoutBookkeeping() {
        UUID pi = startFixture("test-c840-mi-sub-sequential.bpmn", List.of());
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        complete(pi, "utProbe");
        assertTerminalWithNoBookkeeping(pi, "sequential MI subprocess normal completion (pg)");
    }

    @Test
    void nestedMiSubprocess_normalCompletion_withoutBookkeeping() {
        UUID pi = startFixture("test-c840-mi-sub-nested.bpmn", List.of(
            jsonVar("items", "[{\"who\":\"a\",\"members\":[\"a1\",\"a2\"]},{\"who\":\"b\",\"members\":[\"b1\"]}]"),
            booleanVar("routeToMi", "true")));
        complete(pi, "utInner");
        complete(pi, "utInner");
        complete(pi, "miTaskA");
        complete(pi, "miTaskA");
        complete(pi, "miTaskA");
        assertTerminalWithNoBookkeeping(pi, "nested MI subprocess normal completion (pg)");
    }

    private static ProcessVariable booleanVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(value);
        return v;
    }

    @Test
    void operatorCancel_miSubprocessMidFlight_withoutBookkeeping() {
        UUID pi = startFixture("test-c840-mi-sub-minimal.bpmn", List.of());
        assertThat(countOf(pi, "utProbe", ActivityStatus.CREATED))
            .as("premise: 3 live MI instances before the cancel").isEqualTo(3L);
        complete(pi, "utProbe");
        assertThat(bookkeepingRows(pi))
            .as("premise: MI bookkeeping rows exist before the cancel; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of MI subprocess mid-flight (pg)");
    }

    @Test
    void operatorCancel_nestedMiSubprocessMidFlight_withoutBookkeeping() {
        UUID pi = startFixture("test-c840-mi-sub-nested.bpmn", List.of(
            jsonVar("items", "[{\"who\":\"a\",\"members\":[\"a1\",\"a2\"]},{\"who\":\"b\",\"members\":[\"b1\"]}]"),
            booleanVar("routeToMi", "true")));
        complete(pi, "utInner");
        complete(pi, "utInner");
        assertThat(countOf(pi, "miTaskA", ActivityStatus.CREATED))
            .as("premise: inner MI instances are live inside both outer instances").isEqualTo(3L);
        assertThat(bookkeepingRows(pi))
            .as("premise: nested MI bookkeeping rows exist before the cancel; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of nested MI subprocess mid-flight (pg)");
    }

    @Test
    void operatorCancel_adhocWithParkedJoins_withoutBookkeeping() {
        UUID pi = startFixture("test-c838-adhoc-errwalk-holds-neighbour-join.bpmn", List.of());
        complete(pi, "taskY");
        complete(pi, "A");
        assertThat(bookkeepingRows(pi))
            .as("premise: parked joins hold arrival rows; rows: %s", bookkeepingDump(pi))
            .isGreaterThan(0L);

        operatorCancel(pi);

        assertTerminalWithNoBookkeeping(pi, "operator cancel of an ad-hoc scope with parked joins (pg)");
    }

    @Test
    void adhocCancelEnd_instanceCompletes_withoutBookkeeping() {
        UUID pi = startFixture("test-c838-adhoc-cancelend-holds-neighbour-join.bpmn", List.of());
        complete(pi, "taskY");
        complete(pi, "A");
        complete(pi, "E");
        complete(pi, "taskAfter");
        assertTerminalWithNoBookkeeping(pi, "ad-hoc cancel-end drive to completion (pg)");
    }

    /**
     * Гонка по G-P на реальном PostgreSQL: две ветки MI завершаются из РАЗНЫХ
     * потоков по общему барьеру — ровно один терминальный переход и зачистка под
     * instance-lock без потери outputCollection. Каждый поток — своя транзакция
     * прод-пути {@code RuntimeService.completeUserTask} (не тест-обёртка).
     */
    @Test
    void twoBranchesFinishConcurrently_singleTerminalCleanup() throws Exception {
        UUID pi = startFixture("test-c840-mi-sub-race.bpmn",
            List.of(jsonVar("items", "[\"t0\",\"t1\"]")));

        List<UUID> taskIds = tx.execute(s ->
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

        java.time.Instant completedAt = tx.execute(s ->
            queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).as("instance completed after the race").isNotNull();
        String resultsJson = tx.execute(s ->
            variableRepository.findByNameAndProcessInstanceId("results", pi).orElseThrow().getTextValue());
        List<String> values = new ObjectMapper().readValue(resultsJson,
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("outputCollection full and ordered by slot").containsExactly("t0", "t1");
        assertThat(bookkeepingRows(pi))
            .as("terminal cleanup survived the concurrent branch completion; leftovers: %s",
                bookkeepingDump(pi))
            .isZero();
    }

    /**
     * Инвариант порядка на терминальном DELETE для строк, ЛЕЖАЩИХ В
     * PERSISTENCE-КОНТЕКСТЕ ещё не сброшенными (та же проверка, что H2-двойник,
     * но на PG16): {@code save()} только планирует INSERT, и терминальный DELETE
     * обязан снести и его. Проверка детерминирована, без гонок и sleep: сохраняем
     * строку, сразу удаляем, форсируем flush и считаем строки raw-JDBC на той же
     * транзакции; после коммита — повторный COUNT снаружи.
     */
    @Test
    void bookkeepingDelete_cleansRowsPendingInPersistenceContext() {
        UUID pi = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
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
        });
        assertThat(bookkeepingRows(pi))
            .as("после коммита остатков быть не должно; leftovers: %s", bookkeepingDump(pi))
            .isZero();
    }
}
