package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-34: НОРМАЛЬНО завершённый инстанс не оставляет непотреблённых подписок.
 *
 * <p>Симптом (WO-OPS-32 §2, корень F2 — 17 нарушений): инстанс, который
 * нормально завершился ({@code completed_at}, НЕ отменён), оставлял в БД
 * {@code message_subscriptions}/{@code signal_subscriptions} с
 * {@code consumed = false}. Инстанс мёртв — доставка ему не нужна, а сообщение,
 * пришедшее после завершения, не должно уходить в мёртвый инстанс (в Zeebe 8.5
 * подписка снимается вместе со scope).
 *
 * <p>Кого чистим и почему именно так:
 * <ul>
 *   <li><b>message-подписки</b> — в kill-пути они уже удаляются
 *       ({@code InstanceCancellationService.kill}), а в пути НОРМАЛЬНОГО
 *       завершения — нет: удаление было только в операторской отмене;</li>
 *   <li><b>signal-подписки</b> — их не удалял НИКТО: ни kill-путь, ни отмена.
 *       C37-3 ({@code ActivityDbOperationsImpl.applyCompletion}) гасит
 *       message-подписки завершённого ХОСТА, но не signal-подписки — поэтому
 *       signal-boundary (activity_id = строка хоста) переживала и хост, и
 *       инстанс, а signal-started event sub-process (activity_id = NULL)
 *       переживала инстанс по построению;</li>
 *   <li><b>event-subprocess-подписки любого вида</b> (activity_id = NULL) —
 *       они «переживают любой отдельный хост» (C37-3 их не трогает), значит
 *       единственное место, где они умирают, — терминальный переход инстанса.</li>
 * </ul>
 *
 * <p>Проверки — ПОСЛЕ КОМИТА (тест НЕ оборачивается в {@code @Transactional}):
 * зачистка обязана быть видна в ПРОД-состоянии, а не только внутри тестовой
 * транзакции. Факты читаются JdbcTemplate-ом (факт БД, не кэша JPA) — тем же
 * приёмом, что {@code FinishedInstanceArrivalCleanupIntegrationTests}.
 *
 * <p>Каждый тест сначала проверяет ПРЕМИССУ (подписка реально создана) —
 * иначе ассерт «подписок нет» проходил бы и на движке, который их не создаёт
 * вовсе (ложный POF по P-67).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CompletedInstanceSubscriptionCleanupIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ActivityService activityService;
    @Autowired private DBService dbService;
    @Autowired private TimerJobExecutor timerJobExecutor;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Таймер-джобы, созданные этим тестом: общая H2-БД делится между тестами,
     * а {@code TimerMessageQueryIntegrationTests.pendingTimerJobIsListed} ждёт
     * «-fired-таймеров нет» (строка, оставленная моим тестом, ломала бы ЧУЖОЙ
     * тест — ровно тот класс проблемы, что P-59). Поэтому джобы, которые создал
     * этот тест, сносятся в {@code @AfterEach} — точечно, по id.
     */
    private final List<UUID> timerJobsToClean = new ArrayList<>();

    @AfterEach
    void cleanOwnTimerJobs() {
        if (!timerJobsToClean.isEmpty()) {
            timerJobRepository.deleteAllById(timerJobsToClean);
            timerJobsToClean.clear();
        }
    }

    // ── помощники ───────────────────────────────────────────────────────────────────────

    private UUID startFixture(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    /** Завершает единственную живую user-task инстанса. */
    private void completeMainTask(UUID pi) {
        ActivityEntity mainTask = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("mainTask"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED mainTask in " + pi));
        runtimeService.completeUserTask(mainTask.getId(), List.of());
    }

    /** Факт БД: строки message-подписок инстанса (все, независимо от consumed). */
    private List<String> messageSubscriptionRows(UUID pi) {
        return jdbcTemplate.query(
            "SELECT message_name || '/consumed=' || consumed || '/activity='"
                + " || COALESCE(CAST(activity_id AS VARCHAR), '-')"
                + " FROM message_subscriptions WHERE process_instance_id = ? ORDER BY message_name",
            (rs, i) -> rs.getString(1), pi);
    }

    /** Факт БД: строки signal-подписок инстанса (все, независимо от consumed). */
    private List<String> signalSubscriptionRows(UUID pi) {
        return jdbcTemplate.query(
            "SELECT signal_name || '/consumed=' || consumed || '/activity='"
                + " || COALESCE(CAST(activity_id AS VARCHAR), '-')"
                + " FROM signal_subscriptions WHERE process_instance_id = ? ORDER BY signal_name",
            (rs, i) -> rs.getString(1), pi);
    }

    private void assertCompletedNormally(UUID pi) {
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("инстанс завершился НОРМАЛЬНО (completed_at проставлен)").isNotNull();
        assertThat(instance.isCancelled())
            .as("инстанс НЕ отменён — чинится путь нормального завершения, не отмены").isFalse();
    }

    // ── тесты ───────────────────────────────────────────────────────────────────────────

    /**
     * Message-подписка event-subprocess: подписка создана при старте, главный поток
     * доходит до конца — подписка обязана исчезнуть вместе с инстансом.
     */
    @Test
    void normallyCompletedInstance_leavesNoMessageSubscription() throws Exception {
        UUID pi = startFixture("test-event-subprocess.bpmn");

        assertThat(messageSubscriptionRows(pi))
            .as("премисса: event-subprocess подписался на message 'cancelOrder'")
            .isNotEmpty();

        completeMainTask(pi);
        assertCompletedNormally(pi);

        assertThat(messageSubscriptionRows(pi))
            .as("нормально завершённый инстанс не оставляет message-подписок")
            .isEmpty();
    }

    /**
     * Signal-подписки инстанса: граница на хосте (activity_id = строка хоста) и
     * signal-started event sub-process (activity_id = NULL). Обе обязаны умереть
     * вместе с инстансом — иначе broadcast сигнала после завершения ушёл бы в
     * мёртвый инстанс.
     */
    @Test
    void normallyCompletedInstance_leavesNoSignalSubscription() throws Exception {
        UUID pi = startFixture("test-ops34-completed-subscriptions.bpmn");

        assertThat(signalSubscriptionRows(pi))
            .as("премисса: инстанс вооружён signal-подписками (граница + event-subprocess)")
            .hasSizeGreaterThanOrEqualTo(2);

        completeMainTask(pi);
        assertCompletedNormally(pi);

        assertThat(signalSubscriptionRows(pi))
            .as("нормально завершённый инстанс не оставляет signal-подписок")
            .isEmpty();
    }

    /**
     * Тот же факт — ГЛАЗАМИ ИНВАРИАНТА утечек (WO-OPS-26/32): проверка
     * «после конца процесса нет живого мусора» не должна находить у завершённого
     * инстанса остатка типа {@code subscription}.
     */
    @Test
    void normallyCompletedInstance_hasNoLeakInvariantSubscriptionViolation() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);

        UUID pi = startFixture("test-event-subprocess.bpmn");
        completeMainTask(pi);
        assertCompletedNormally(pi);

        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbcTemplate, baseline);

        assertThat(result.own())
            .as("инвариант утечек: у нормально завершённого инстанса нет остатка подписок")
            .filteredOn(v -> "subscription".equals(v.type()))
            .isEmpty();
        assertThat(result.own())
            .as("инвариант утечек: у нормально завершённого инстанса нет ВООБЩЕ остатка; нарушения: %s",
                result.own())
            .isEmpty();
    }

    /**
     * Поведенческая сторона: сообщение, пришедшее ПОСЛЕ нормального завершения,
     * не должно уходить в мёртвый инстанс. До фикса подписка жила, корреляция
     * «срабатывала» (коррелированная = 1) и движок пытался запустить
     * event-subprocess в завершённом инстансе (там его глушит guard
     * «instance already completed»). После фикса подписки нет — коррелировать
     * нечего.
     */
    @Test
    void messagePublishedAfterCompletion_doesNotReachTheDeadInstance() throws Exception {
        UUID pi = startFixture("test-event-subprocess.bpmn");
        completeMainTask(pi);
        assertCompletedNormally(pi);

        int correlated = activityService
            .publishMessage("cancelOrder", null, pi, List.of())
            .getCorrelated();

        assertThat(correlated)
            .as("после нормального завершения подписки нет — сообщение некому коррелировать")
            .isZero();
        assertThat(activities(pi))
            .as("в мёртвый инстанс не доехало ни одного элемента")
            .allSatisfy(a -> assertThat(a.getStatus()).isNotIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
    }

    /**
     * Взаимодействие ДВУХ правил, которые теперь разведены по моментам:
     * C37-3 (WO-C8-37) — подписка закрытого ХОСТА закрывается при завершении
     * хоста ({@code consumed=true}, история сохраняется, строка остаётся), и
     * WO-OPS-34 — терминальный переход ИНСТАНСА удаляет все его подписки.
     *
     * <p>Сценарий — тот же, что у переработанного теста WO-C8-37
     * {@code ContainerSubscriptionCloseIntegrationTests#boundarySubscriptionOfCompletedHost_closedWithoutAnyCorrelate}:
     * хост с message-границей завершается напрямую (корреляции не было), инстанс
     * после этого ещё жив. Здесь закрываются оба правила: сначала видно, что
     * подписка хоста помечена consumed (C37-3), потом инстанс завершается — и
     * подписки нет вовсе (WO-OPS-34). Этим же тестом закрывается покрытие
     * «завершение задачи завершает инстанс», ушедшее из чужого теста при
     * переработке его фикстуры (см. отчёт §4.3).
     */
    @Test
    void completedInstance_dropsAlsoTheHostSubscriptionClosedByC37() throws Exception {
        UUID pi = startFixture("test-c837-timeout-then-message.bpmn");

        ActivityEntity work = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("work"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED work in " + pi));
        runtimeService.completeUserTask(work.getId(), List.of());

        assertThat(messageSubscriptionRows(pi))
            .as("C37-3: подписка закрытого хоста помечена consumed=true (история), а не удалена")
            .hasSize(1)
            .allMatch(r -> r.contains("consumed=TRUE"));
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("инстанс ещё жив (вторая задача ждёт)")
            .isNull();

        ActivityEntity work2 = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("work2"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED work2 in " + pi));
        runtimeService.completeUserTask(work2.getId(), List.of());
        assertCompletedNormally(pi);

        assertThat(messageSubscriptionRows(pi))
            .as("терминальный переход инстанса удаляет и уже закрытую C37-3 подписку")
            .isEmpty();
    }

    /**
     * WO-OPS-34, пункт 2 «что сделать»: ПРОВЕРКА остальных instance-scoped
     * «подписок» — таймер-джобов инстанса.
     *
     * <p>ФАКТ (измерено, а не выведено из кода): таймер-джоб нормально
     * завершённого инстанса ОСТАЁТСЯ в БД (удаление таймеров есть только в
     * kill-пути — {@code InstanceCancellationService.kill} — и в операторской
     * отмене; на нормальном завершении никто их не снимает).
     *
     * <p>ПОВЕДЕНИЕ (главное): такой джоб НЕ является каналом доставки в мёртвый
     * инстанс — выстрел гасится guard'ом на стороне хоста
     * ({@code EventTrigger.fireBoundary}: хост COMPLETED/CANCELLED → «Boundary
     * fired but host activity … ignoring»). Проверка ниже — живая: джоб
     * действительно выстреливается, и инстанс от этого не меняется.
     *
     * <p>Почему джоб не удаляется в этом WO: таймер — не подписка в смысле
     * инварианта утечек (инвариант считает таймеры только на ЖИВОЙ activity —
     * {@code LeakInvariantChecker}: JOIN activities + {@code status IN
     * ('CREATED','IN_PROGRESS')}; у event-subprocess-таймеров {@code activity_id}
     * = NULL, они не джойнятся никогда), а удаление таймеров на нормальном
     * завершении меняет поведение повторяющихся таймеров и ломает чужие тесты,
     * считающие строки расписания ({@code ControlProcess}/{@code MiReenter}
     * ждут точный {@code findAll()}). Это — находка рядом со scope (V7),
     * зафиксирована в отчёте, отдельным WO.
     */
    @Test
    void leftoverTimerJobOfCompletedInstance_isInert() throws Exception {
        UUID pi = startFixture("test-boundary.bpmn");   // userTask1 + граница-таймер PT10M

        ActivityEntity userTask = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("userTask1"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED userTask1 in " + pi));
        runtimeService.completeUserTask(userTask.getId(), List.of());
        assertCompletedNormally(pi);

        List<TimerJob> armed = dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).stream()
            .filter(j -> pi.equals(j.getProcessInstanceId()))
            .toList();
        assertThat(armed)
            .as("факт: таймер-джоб завершённого инстанса остаётся в БД (чистка — только в kill-пути)")
            .isNotEmpty();
        armed.forEach(j -> timerJobsToClean.add(j.getId()));

        int activitiesBefore = activities(pi).size();
        armed.forEach(timerJobExecutor::fire);

        assertThat(activities(pi))
            .as("выстрел таймера мёртвого инстанса не создал ни одной activity")
            .hasSize(activitiesBefore);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("инстанс остался завершённым — доставка в него не уехала")
            .isNotNull();
    }
}
