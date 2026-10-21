package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PgItIsolation;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-38: kill-путь и операторская отмена не снимают signal-подписки инстанса.
 *
 * <p><b>Симптом (WO-OPS-34, находка V7 «асимметрия путей»).</b> Нормальное
 * завершение снимает message- И signal-подписки ({@code
 * FinishedInstanceSubscriptionCleaner}, WO-OPS-34), а kill-путь
 * ({@code InstanceCancellationService.kill}) и операторская отмена
 * ({@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}) снимали
 * ТОЛЬКО message-подписки. Signal-подписки отменённого/убитого инстанса
 * оставались: broadcast сигнала уходил в мёртвый инстанс, а инвариант утечек
 * (G-P) видел остаток типа {@code subscription}.
 *
 * <p><b>Одно правило.</b> «Терминальный инстанс не держит подписок» — одно, и
 * оно живёт в терминальном переходе отмены ({@code
 * ProcessInstanceDbOperationsImpl.cancelProcessInstance}), а не в каждом пути
 * отмены отдельно.
 *
 * <p><b>Проверки — ПОСЛЕ КОМИТА</b> (тест НЕ оборачивается в
 * {@code @Transactional}): зачистка обязана быть видна в ПРОД-состоянии, а не
 * только внутри тестовой транзакции. Факты читаются JdbcTemplate'ом — тем же
 * приёмом, что {@code CompletedInstanceSubscriptionCleanupIntegrationTests}
 * (WO-OPS-34).
 *
 * <p><b>Премисса проверяется ПЕРЕД действием</b> (P-67): иначе ассерт «подписок
 * нет» проходил бы и на движке, который их не создаёт вовсе.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CancelledInstanceSubscriptionCleanupIntegrationTests {

    /** Сигнал из фикстуры test-ops38-cancelled-subscriptions.bpmn. */
    private static final String SIGNAL_NAME = "ops38Sig";
    /** Сигнал из фикстуры test-ops38-call-child.bpmn. */
    private static final String CHILD_SIGNAL_NAME = "ops38ChildSig";

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private DBService dbService;
    @Autowired private ActivityService activityService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private SignalSubscriptionRepository signalSubscriptionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    /** Коды определений, которые задеплоил этот тест. */
    private static final String KEY_CANCELLED = "test-ops38-cancelled-subscriptions";
    private static final String KEY_CALL_CHILD = "ops38-call-child";
    private static final String KEY_CALL_PARENT = "ops38-call-parent";

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    /**
     * Самоочистка: H2-БД делится между тестами модуля, и остаток этого теста
     * (особенно строка ДОЧЕРНЕГО инстанса call activity — она ссылается на
     * activity родителя, {@code fk_process_instances__parent_activity_id}) ломает
     * чужие чистки вида {@code DELETE FROM activities}. Чистим ДЕТЕЙ ПЕРВЫМИ —
     * тот же порядок, что требует {@link PgItIsolation#deleteDeployment}.
     *
     * <p>Зачистка идёт ПОСЛЕ проверки инварианта утечек: слушатель инварианта
     * (order 3000) срабатывает до {@code @AfterEach}.
     */
    @AfterEach
    void cleanOwnDeployments() {
        for (String key : List.of(KEY_CALL_CHILD, KEY_CALL_PARENT, KEY_CANCELLED)) {
            jdbcTemplate.update("DELETE FROM variable_history WHERE process_instance_id IN ("
                + "SELECT id FROM process_instances WHERE process_definition_id IN ("
                + "SELECT id FROM process_definitions WHERE code = ?))", key);
            PgItIsolation.deleteDeployment(jdbcTemplate, key);
        }
    }

    // ── помощники ───────────────────────────────────────────────────────────────────────

    private ProcessDefinition deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        return processDefinitionService.addProcessDefinition(bpmn);
    }

    private UUID start(ProcessDefinition model) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    /** Факт БД: строки signal-подписок инстанса (все, независимо от consumed). */
    private List<String> signalSubscriptionRows(UUID pi) {
        return jdbcTemplate.query(
            "SELECT signal_name || '/consumed=' || consumed || '/activity='"
                + " || COALESCE(CAST(activity_id AS VARCHAR), '-')"
                + " FROM signal_subscriptions WHERE process_instance_id = ? ORDER BY signal_name",
            (rs, i) -> rs.getString(1), pi);
    }

    /** Факт БД: строки message-подписок инстанса (все, независимо от consumed). */
    private List<String> messageSubscriptionRows(UUID pi) {
        return jdbcTemplate.query(
            "SELECT message_name || '/consumed=' || consumed || '/activity='"
                + " || COALESCE(CAST(activity_id AS VARCHAR), '-')"
                + " FROM message_subscriptions WHERE process_instance_id = ? ORDER BY message_name",
            (rs, i) -> rs.getString(1), pi);
    }

    /**
     * Операторская отмена — тот же порядок, что в REST-операции отмены инстанса
     * ({@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}):
     * гашение активностей → снос таймеров → снос message-подписок → отмена.
     *
     * <p>Транзакция — своя (как в REST, где метод помечен {@code @Transactional}):
     * bulk-DELETE подписок идёт мимо persistence-контекста JPA и без транзакции
     * не выполняется вовсе.
     */
    private void operatorCancel(UUID pi) {
        tx.executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });
    }

    private UUID childOf(UUID parent) {
        List<ProcessInstanceEntity> children = processInstanceRepository.findAll(
            ProcessInstanceRepository.byParentProcessInstanceId(parent));
        assertThat(children).as("один дочерний инстанс на call activity").hasSize(1);
        return children.get(0).getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    // ── путь 1: операторская отмена ────────────────────────────────────────────────────

    /**
     * Путь 1 (операторская отмена): отменённый инстанс не оставляет
     * signal-подписок — ни подписки границы, ни подписки signal-started ESP.
     */
    @Test
    void cancelledInstance_leavesNoSignalSubscription() throws Exception {
        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));

        assertThat(signalSubscriptionRows(pi))
            .as("премисса: инстанс вооружён signal-подписками (граница + event-subprocess)")
            .hasSizeGreaterThanOrEqualTo(2);

        operatorCancel(pi);

        assertThat(queryService.getProcessInstance(pi).isCancelled())
            .as("инстанс отменён").isTrue();
        assertThat(signalSubscriptionRows(pi))
            .as("отменённый инстанс не оставляет signal-подписок")
            .isEmpty();
    }

    /** Тот же факт для message-подписок (правило «терминальный инстанс не держит подписок»). */
    @Test
    void cancelledInstance_leavesNoMessageSubscription() throws Exception {
        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));

        operatorCancel(pi);

        assertThat(queryService.getProcessInstance(pi).isCancelled())
            .as("инстанс отменён").isTrue();
        assertThat(messageSubscriptionRows(pi))
            .as("отменённый инстанс не оставляет message-подписок")
            .isEmpty();
    }

    /**
     * G-P: тот же факт ГЛАЗАМИ ИНВАРИАНТА утечек (WO-OPS-26/32) — у завершённого
     * (отменённого) инстанса не остаётся остатка типа {@code subscription}.
     */
    @Test
    void cancelledInstance_hasNoLeakInvariantSubscriptionViolation() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);

        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));
        operatorCancel(pi);

        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbcTemplate, baseline);

        assertThat(result.own())
            .as("инвариант утечек: у отменённого инстанса нет остатка подписок; нарушения: %s",
                result.own())
            .filteredOn(v -> "subscription".equals(v.type()))
            .isEmpty();
    }

    /**
     * Поведенческая сторона: broadcast сигнала ПОСЛЕ отмены не должен доходить
     * до мёртвого инстанса. До фикса подписка жила, broadcast её цеплял
     * (у границы — помечал подписку consumed в мёртвом инстансе; у
     * signal-started ESP — звал trigger, который гасит guard «instance already
     * completed»). После фикса подписки нет — цеплять нечего.
     */
    @Test
    void signalBroadcastAfterCancel_doesNotReachTheDeadInstance() throws Exception {
        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));
        operatorCancel(pi);

        int activitiesBefore = activities(pi).size();
        activityService.broadcastSignal(SIGNAL_NAME, List.of());

        assertThat(activities(pi))
            .as("в мёртвый инстанс не доехало ни одного элемента")
            .hasSize(activitiesBefore);
        assertThat(signalSubscriptionRows(pi))
            .as("у мёртвого инстанса нет ни одной signal-подписки — broadcast нечего цеплять")
            .isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("инстанс остался завершённым — доставка в него не уехала")
            .isNotNull();
    }

    // ── путь 2: kill (каскад отмены дерева call activity) ──────────────────────────────

    /**
     * Путь 2 (kill-путь): каскадная отмена родителя УБИВАЕТ дочерний инстанс
     * call activity ({@code InstanceCancellationService.kill}) — и убитый ребёнок
     * не должен держать свои signal-подписки.
     */
    @Test
    void killedChildInstance_leavesNoSignalSubscription() throws Exception {
        deploy("test-ops38-call-child.bpmn");
        UUID parent = start(deploy("test-ops38-call-parent.bpmn"));
        UUID child = childOf(parent);

        assertThat(signalSubscriptionRows(child))
            .as("премисса: дочерний инстанс вооружён signal-подписками (граница + event-subprocess)")
            .hasSizeGreaterThanOrEqualTo(2);

        operatorCancel(parent);

        assertThat(queryService.getProcessInstance(child).isCancelled())
            .as("дочерний инстанс убит каскадом отмены").isTrue();
        assertThat(signalSubscriptionRows(child))
            .as("убитый дочерний инстанс не оставляет signal-подписок")
            .isEmpty();
        assertThat(activities(child))
            .as("в убитом ребёнке нет живых activity")
            .noneMatch(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS);
    }

    /**
     * Тот же kill-путь — но проверка через ИНВАРИАНТ утечек (G-P): у обоих
     * инстансов (родитель и убитый ребёнок) не остаётся остатка подписок.
     */
    @Test
    void killedChildInstance_hasNoLeakInvariantSubscriptionViolation() throws Exception {
        deploy("test-ops38-call-child.bpmn");
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);

        UUID parent = start(deploy("test-ops38-call-parent.bpmn"));
        UUID child = childOf(parent);
        operatorCancel(parent);

        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbcTemplate, baseline);

        assertThat(result.own())
            .as("инвариант утечек: у отменённого родителя и убитого ребёнка нет остатка подписок; нарушения: %s",
                result.own())
            .filteredOn(v -> "subscription".equals(v.type()))
            .isEmpty();
    }

    /**
     * Kill-путь со стороны СИГНАЛА: broadcast имени сигнала ребёнка после каскада
     * отмены не должен оживлять убитого ребёнка (у него не осталось подписок).
     */
    @Test
    void childSignalBroadcastAfterParentCancel_doesNotReachTheDeadChild() throws Exception {
        deploy("test-ops38-call-child.bpmn");
        UUID parent = start(deploy("test-ops38-call-parent.bpmn"));
        UUID child = childOf(parent);
        operatorCancel(parent);

        int childActivitiesBefore = activities(child).size();
        activityService.broadcastSignal(CHILD_SIGNAL_NAME, List.of());

        assertThat(activities(child))
            .as("broadcast сигнала ребёнка не оживил убитый инстанс")
            .hasSize(childActivitiesBefore);
        assertThat(signalSubscriptionRows(child))
            .as("у убитого ребёнка нет signal-подписок — broadcast нечего цеплять")
            .isEmpty();
    }
}
