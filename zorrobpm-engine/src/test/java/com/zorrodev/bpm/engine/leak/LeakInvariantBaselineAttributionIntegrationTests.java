package com.zorrodev.bpm.engine.leak;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-32 — регресс-guard на АТРИБУЦИЮ нарушений (то, что сломано было в
 * WO-OPS-26 и не было замечено: baseline снимался пустым, и проверка
 * приписывала остаток ВСЕЙ общей БД тому тесту, который эти инстансы не
 * создавал).
 *
 * <p>Сценарий: инстанс создан ДО теста (данные закоммичены — класс НЕ
 * транзакционный) и ЖИВ (пользовательская задача ждёт). Он обязан попасть в
 * baseline следующего теста. Если baseline пуст или не снят — инстанс будет
 * приписан тесту как «свой», и утечка чужого инстанса станет «находкой» этого
 * теста (ровно то, что давало 1077 ложных «нарушений» в полном прогоне).
 *
 * <p>Третий тест завершает этот инстанс ВНУТРИ теста: инстанс existed-before,
 * но завершён в окне теста — по правильной атрибуции он «чужой» (только отчёт),
 * по старой — «свой» (принуждается). С живой activity в остатке разница
 * наблюдаема.
 *
 * <p>Класс намеренно НЕ транзакционный: инстанс должен дожить до следующего
 * теста (в транзакции он откатился бы). Уборка — в {@code @AfterAll}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class LeakInvariantBaselineAttributionIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID preExistingInstance;

    @Test
    @Order(1)
    void createInstanceBeforeTheTest() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-usertask-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        preExistingInstance = runtimeService.startProcessInstance(dto).getId();
        assertThat(preExistingInstance).isNotNull();
        assertThat(queryService.getProcessInstance(preExistingInstance).getCompletedAt())
            .as("инстанс ДО теста жив (ждёт пользователя)").isNull();
    }

    @Test
    @Order(2)
    void baselineOfThisTest_containsTheInstanceThatExistedBeforeIt() {
        String key = getClass().getName()
            + "#baselineOfThisTest_containsTheInstanceThatExistedBeforeIt";
        LeakInvariantChecker.Baseline baseline = LeakInvariantTestListener.BASELINES.get(key);
        assertThat(baseline)
            .as("baseline снят до теста (иначе проверка считала бы «своими» все"
                + " завершённые инстансы общей БД)")
            .isNotNull();
        assertThat(baseline.instanceIds())
            .as("инстанс, существовавший до теста, обязан быть в baseline")
            .contains(preExistingInstance);
        assertThat(baseline.completedInstanceIds())
            .as("незавершённый до теста инстанс обязан быть в baseline как ЖИВОЙ")
            .doesNotContain(preExistingInstance);
    }

    @Test
    @Order(3)
    void instanceThatExistedBeforeTheTest_isForeign_andNotOwn() {
        // Живая activity в остатке: без остатка атрибуция не наблюдаема.
        UUID token = jdbc.queryForObject(
            "SELECT token FROM activities WHERE process_instance_id = ? LIMIT 1",
            UUID.class, preExistingInstance);
        jdbc.update("INSERT INTO activities (id, process_instance_id, bpmn_element_id,"
            + " created_at, completed_at, type, status, token) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), preExistingInstance, "preExistingResidue", Instant.now(), null,
            "USER_TASK", "CREATED", token);

        // Завершаем чужой (созданный до теста) инстанс ВНУТРИ теста.
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(preExistingInstance);
        List<UserTask> tasks = queryService.findUserTasks(query, null).getData();
        assertThat(tasks).hasSize(1);
        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(preExistingInstance).getCompletedAt())
            .as("инстанс завершён в окне теста").isNotNull();

        String key = getClass().getName() + "#instanceThatExistedBeforeTheTest_isForeign_andNotOwn";
        LeakInvariantChecker.Baseline baseline = LeakInvariantTestListener.BASELINES.get(key);
        assertThat(baseline).isNotNull();
        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbc, baseline);
        assertThat(result.own())
            .as("инстанс, созданный ДО теста, не приписывается тесту как «свой»")
            .extracting(LeakInvariantChecker.Violation::instanceId)
            .doesNotContain(preExistingInstance);
        assertThat(result.foreign())
            .as("он же — «чужой»: создан до теста, завершён в его окне")
            .extracting(LeakInvariantChecker.Violation::instanceId)
            .contains(preExistingInstance);
    }

    @AfterAll
    void cleanup() {
        if (preExistingInstance == null) {
            return;
        }
        // Порядок — по FK: user_tasks/service_tasks → incidents → activities →
        // tokens → variables → process_instances.
        jdbc.update("DELETE FROM user_tasks WHERE process_instance_id = ?", preExistingInstance);
        jdbc.update("DELETE FROM service_tasks WHERE process_instance_id = ?", preExistingInstance);
        jdbc.update("DELETE FROM incidents WHERE activity_id IN (SELECT id FROM activities"
            + " WHERE process_instance_id = ?)", preExistingInstance);
        List<UUID> tokens = jdbc.query(
            "SELECT DISTINCT token FROM activities WHERE process_instance_id = ?"
                + " AND token IS NOT NULL",
            (rs, rowNum) -> (UUID) rs.getObject("token"), preExistingInstance);
        jdbc.update("DELETE FROM activities WHERE process_instance_id = ?", preExistingInstance);
        for (UUID token : tokens) {
            jdbc.update("DELETE FROM tokens WHERE id = ?", token);
        }
        jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", preExistingInstance);
        jdbc.update("DELETE FROM process_instances WHERE id = ?", preExistingInstance);
    }
}
