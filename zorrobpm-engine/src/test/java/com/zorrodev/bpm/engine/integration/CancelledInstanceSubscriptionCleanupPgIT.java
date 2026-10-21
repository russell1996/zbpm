package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PgItIsolation;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-38 на реальном PostgreSQL: kill-путь и операторская отмена снимают
 * signal-подписки инстанса.
 *
 * <p>Зачем PG, а не только H2 (V11, P-17/P-22): зачистка — это BULK-DELETE
 * ({@code DELETE FROM … WHERE process_instance_id = ?}) мимо persistence-контекста
 * JPA; его видимость (в том числе в @Transactional-тесте) зависит от фазы
 * коммита, и на H2 это выглядит иначе, чем на боевой СУБД. H2-двойник —
 * {@code CancelledInstanceSubscriptionCleanupIntegrationTests}; здесь то же самое
 * проверяется на настоящем PostgreSQL 16, по ЗАКОММИЧЕННОМУ состоянию
 * (старт/отмена — отдельными транзакциями), плюс сам инвариант утечек
 * (WO-OPS-26/32) на тех же данных.
 */
@Tag("pg")
public class CancelledInstanceSubscriptionCleanupPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private JdbcTemplate jdbc;
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
     * Самоочистка: общая PG-БД делится между всеми PG-классами, и остаток этого
     * теста ломает ЧУЖИЕ чистки.
     *
     * <p>Механика поломки (воспроизведена): строка ДОЧЕРНЕГО инстанса call
     * activity ссылается на activity родителя
     * ({@code fk_process_instances__parent_activity_id}), поэтому
     * {@code Rel54BatchThroughputPgIT.setUp} с его {@code DELETE FROM activities}
     * (до {@code DELETE FROM process_instances}) падал на FK:
     * {@code update or delete on table "activities" violates foreign key constraint
     * "fk_process_instances__parent_activity_id"}. Чистим ДЕТЕЙ ПЕРВЫМИ — так же,
     * как требует {@link PgItIsolation#deleteDeployment} (дети → родители).
     */
    @AfterEach
    void cleanOwnDeployments() {
        for (String key : List.of(KEY_CALL_CHILD, KEY_CALL_PARENT, KEY_CANCELLED)) {
            jdbc.update("DELETE FROM variable_history WHERE process_instance_id IN ("
                + "SELECT id FROM process_instances WHERE process_definition_id IN ("
                + "SELECT id FROM process_definitions WHERE code = ?))", key);
            PgItIsolation.deleteDeployment(jdbc, key);
        }
    }

    private ProcessDefinition deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        return tx.execute(s -> processDefinitionService.addProcessDefinition(bpmn));
    }

    private UUID start(ProcessDefinition model) {
        return tx.execute(s -> {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
    }

    /** Операторская отмена — тот же порядок, что в REST-операции отмены инстанса. */
    private void operatorCancel(UUID pi) {
        tx.executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });
    }

    /** Факт БД PostgreSQL: строки signal-подписок инстанса (все, независимо от consumed). */
    private long signalSubscriptionRows(UUID pi) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM signal_subscriptions WHERE process_instance_id = ?", Long.class, pi);
        return n == null ? 0L : n;
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

    /** Путь 1 (операторская отмена): signal-подписки отменённого инстанса сняты. */
    @Test
    void cancelledInstance_dropsSignalSubscription() throws Exception {
        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));

        assertThat(signalSubscriptionRows(pi))
            .as("премисса: инстанс вооружён signal-подписками (граница + event-subprocess)")
            .isGreaterThanOrEqualTo(2L);

        operatorCancel(pi);

        assertThat(queryService.getProcessInstance(pi).isCancelled())
            .as("инстанс отменён").isTrue();
        assertThat(signalSubscriptionRows(pi))
            .as("отменённый инстанс не оставляет signal-подписок")
            .isZero();
    }

    /** Путь 2 (kill-путь): signal-подписки убитого дочернего инстанса сняты. */
    @Test
    void killedChildInstance_dropsSignalSubscription() throws Exception {
        deploy("test-ops38-call-child.bpmn");
        UUID parent = start(deploy("test-ops38-call-parent.bpmn"));
        UUID child = childOf(parent);

        assertThat(signalSubscriptionRows(child))
            .as("премисса: дочерний инстанс вооружён signal-подписками")
            .isGreaterThanOrEqualTo(2L);

        operatorCancel(parent);

        assertThat(queryService.getProcessInstance(child).isCancelled())
            .as("дочерний инстанс убит каскадом отмены").isTrue();
        assertThat(signalSubscriptionRows(child))
            .as("убитый дочерний инстанс не оставляет signal-подписок")
            .isZero();
        assertThat(activities(child))
            .as("в убитом ребёнке нет живых activity")
            .noneMatch(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS);
    }

    /** Тот же факт — глазами инварианта утечек, на данных PostgreSQL. */
    @Test
    void cancelledInstance_hasNoLeakInvariantSubscriptionViolation() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);

        UUID pi = start(deploy("test-ops38-cancelled-subscriptions.bpmn"));
        operatorCancel(pi);

        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbc, baseline);

        assertThat(result.own())
            .as("инвариант утечек на PostgreSQL: у отменённого инстанса нет остатка подписок; нарушения: %s",
                result.own())
            .filteredOn(v -> "subscription".equals(v.type()))
            .isEmpty();
    }
}
