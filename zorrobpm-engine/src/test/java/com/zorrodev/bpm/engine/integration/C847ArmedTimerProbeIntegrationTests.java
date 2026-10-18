package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-47, шаг 1 — установить факт: оставляет ли какой-либо путь завершения
 * процесса взведённый (fired=false) timer-job на ЖИВОЙ activity.
 *
 * <p>Проверяются 6 путей из WO: обычное завершение задачи с границей-таймером,
 * MI-задача, ранний выход MI-подпроцесса (completionCondition), terminate end,
 * прерывающая error-граница, операторская отмена. Таймеры PT10M — за тест не
 * выстреливают сами.
 *
 * <p>Ожидание (гипотеза «не баг»): все activity завершённого инстанса
 * терминальны, armed-таймеров на живых нет. Несработавшие timer-строки на
 * терминальных activity (trimmed-хвост) — НЕ нарушение: их поздний выстрел
 * инертный (guards в fireBoundary/signal/triggerEventSubprocess), чистка —
 * retention. Если какой-то путь даст живую activity + armed-таймер — тест
 * покраснеет, и это настоящий RED для фикса.
 *
 * <p>Методология: БЕЗ {@code @Transactional} — timer-job пишется прод-кодом
 * через JPA {@code save()} без гарантированного flush, и raw-JDBC чтение из
 * той же незакоммиченной транзакции видит строки только при случайном flush
 * (P2/P3 видели, P1/P4/P5 — нет; та же строка, тот же метод записи). Чтение
 * закоммиченных строк детерминировано. Чистка — за собой в {@code @AfterEach}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class C847ArmedTimerProbeIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private DBService dbService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final List<UUID> startedInstances = new ArrayList<>();

    @BeforeEach
    void cleanBefore() {
        cleanUp();
    }

    @AfterEach
    void cleanAfter() {
        cleanUp();
    }

    private void cleanUp() {
        // Наши определения — только c847-* (уникальны для этого класса).
        // Подбираем и остатки упавших прогонов, не только tracked-инстансы.
        List<UUID> leftovers = jdbc.queryForList(
            "SELECT pi.id FROM process_instances pi JOIN process_definitions pd"
                + " ON pd.id = pi.process_definition_id WHERE pd.code LIKE 'c847-%'",
            UUID.class);
        List<UUID> all = new ArrayList<>(startedInstances);
        for (UUID pi : leftovers) {
            if (!all.contains(pi)) {
                all.add(pi);
            }
        }
        for (UUID pi : all) {
            jdbc.update("DELETE FROM timer_jobs WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM message_subscriptions WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM signal_subscriptions WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM parallel_gateways WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM incidents WHERE activity_id IN "
                + "(SELECT id FROM activities WHERE process_instance_id = ?)", pi);
            jdbc.update("DELETE FROM user_tasks WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM service_tasks WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
            jdbc.update("DELETE FROM events WHERE process_instance_id = ?", pi);
            // FK activities.token → tokens: id токенов запоминаем ДО удаления activity.
            List<UUID> tokenIds = jdbc.queryForList(
                "SELECT DISTINCT token FROM activities WHERE process_instance_id = ? AND token IS NOT NULL",
                UUID.class, pi);
            jdbc.update("DELETE FROM activities WHERE process_instance_id = ?", pi);
            // Два прохода: сначала дети, потом родители (tokens.parent_id).
            for (int pass = 0; pass < 2; pass++) {
                for (UUID t : tokenIds) {
                    jdbc.update("DELETE FROM tokens WHERE id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM activities WHERE token = ?)"
                        + " AND NOT EXISTS (SELECT 1 FROM tokens t2 WHERE t2.parent_id = ?)", t, t, t);
                }
            }
            jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
        }
        startedInstances.clear();
        jdbc.update("DELETE FROM process_definitions WHERE code LIKE 'c847-%'");
    }

    private UUID deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        startedInstances.add(pi);
        return pi;
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private long liveActivities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .count();
    }

    /**
     * Точный SQL инварианта OPS-26 (LeakInvariantChecker, блок (в), финальное
     * правило): unfired timer-job, привязанный к ЖИВОЙ activity завершённого
     * инстанса. Сработавшие (fired=true) и джобы на терминальных activity —
     * trimmed-хвост, не нарушение.
     */
    private long armedTimerViolations(UUID pi) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs j JOIN activities a ON a.id = j.activity_id"
                + " WHERE j.process_instance_id = ? AND j.fired = false"
                + " AND a.status IN ('CREATED','IN_PROGRESS')",
            Long.class, pi);
    }

    /** Несработавшие timer-строки инстанса (включая trimmed-хвост) — для протокола. */
    private long unfiredJobs(UUID pi) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE process_instance_id = ? AND fired = false",
            Long.class, pi);
    }

    private void assertNoArmedTimerOnLiveActivity(UUID pi, String path) {
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as(path + ": instance must be completed for the check to be meaningful")
            .isNotNull();
        assertThat(liveActivities(pi))
            .as(path + ": no live activities in a completed instance")
            .isZero();
        assertThat(armedTimerViolations(pi))
            .as(path + ": no armed timer on a live activity after completion")
            .isZero();
    }

    @Test
    void p1_normalTaskCompletion_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-boundary.bpmn"));
        List<ActivityEntity> tasks = activities(pi, "userTask1", ActivityStatus.CREATED);
        assertThat(tasks).hasSize(1);
        assertThat(unfiredJobs(pi)).as("P1: boundary timer armed while the task is live").isEqualTo(1);

        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());

        assertNoArmedTimerOnLiveActivity(pi, "P1");
    }

    @Test
    void p2_miTaskCompletion_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-mi-boundary.bpmn"));
        List<ActivityEntity> tasks = activities(pi, "miTask", ActivityStatus.CREATED);
        assertThat(tasks).as("P2: 2 MI instances").hasSize(2);
        assertThat(unfiredJobs(pi)).as("P2: boundary timers armed").isGreaterThanOrEqualTo(1);

        for (ActivityEntity t : tasks) {
            runtimeService.completeUserTask(t.getId(), List.of());
        }

        assertNoArmedTimerOnLiveActivity(pi, "P2");
    }

    @Test
    void p3_miSubprocessEarlyExit_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-c847-mi-early-exit.bpmn"));
        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).as("P3: 2 MI-subprocess instances running").hasSize(2);
        assertThat(unfiredJobs(pi)).as("P3: container boundary timers armed").isGreaterThanOrEqualTo(1);

        // Завершаем ОДИН инстанс — completionCondition (>=1) гасит второй досрочно.
        runtimeService.completeServiceTask(w.get(0).getId(), List.of());
        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).as("P3: early exit continued past the MI body").hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertNoArmedTimerOnLiveActivity(pi, "P3");
    }

    @Test
    void p4_terminateEnd_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-c847-terminate.bpmn"));
        List<ActivityEntity> tasks = activities(pi, "innerTask", ActivityStatus.CREATED);
        assertThat(tasks).hasSize(1);
        assertThat(unfiredJobs(pi)).as("P4: boundary timer armed while the task is live").isEqualTo(1);

        // Завершение задачи ведёт к terminate end внутри подпроцесса.
        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());

        assertNoArmedTimerOnLiveActivity(pi, "P4");
    }

    @Test
    void p5_errorBoundary_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-c847-error.bpmn"));
        assertThat(activities(pi, "slowTask", ActivityStatus.CREATED)).hasSize(1);
        assertThat(unfiredJobs(pi)).as("P5: slow-task boundary timer armed").isGreaterThanOrEqualTo(1);

        // Завершение медленной задачи открывает быструю; её error end бросает
        // E-847 — прерывающая граница гасит scope вместе с armed-таймером.
        UUID slow = activities(pi, "slowTask", ActivityStatus.CREATED).get(0).getId();
        runtimeService.completeUserTask(slow, List.of());
        UUID fast = activities(pi, "fastTask", ActivityStatus.CREATED).get(0).getId();
        runtimeService.completeUserTask(fast, List.of());

        assertNoArmedTimerOnLiveActivity(pi, "P5");
    }

    /**
     * P6: операторская отмена инстанса с armed-таймером. Порядок вызовов — как в
     * прод-пути {@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}
     * (минус authz/audit/rest): отмена активных activity, удаление таймеров и
     * подписок, отмена инстанса.
     */
    @Test
    void p6_operatorCancel_leavesNoArmedTimerOnLiveActivity() throws Exception {
        UUID pi = start(deploy("test-boundary.bpmn"));
        assertThat(activities(pi, "userTask1", ActivityStatus.CREATED)).hasSize(1);
        assertThat(unfiredJobs(pi)).as("P6: boundary timer armed while the task is live").isEqualTo(1);

        org.springframework.transaction.support.TransactionTemplate tx =
            new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.execute(s -> {
            dbService.lockProcessInstance(pi);
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
            return null;
        });

        assertThat(unfiredJobs(pi)).as("P6: operator cancel deletes all timer jobs of the instance").isZero();
        assertThat(liveActivities(pi)).as("P6: no live activities after operator cancel").isZero();
        assertThat(armedTimerViolations(pi)).as("P6: no armed timer on a live activity after cancel").isZero();
    }
}
