package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
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
 * WO-OPS-34 на реальном PostgreSQL: нормальное завершение инстанса снимает его
 * instance-scoped подписки (message/signal).
 *
 * <p>Зачем PG, а не только H2 (V11, P-17/P-22): фикс — это BULK-DELETE
 * ({@code DELETE FROM … WHERE process_instance_id = ?}) в терминальной транзакции.
 * Он идёт мимо persistence-контекста JPA, а его видимость (в том числе в
 * @Transactional-тесте) зависит от фазы коммита — на H2 это выглядит иначе, чем
 * на боевой СУБД. H2-двойник — {@code CompletedInstanceSubscriptionCleanupIntegrationTests};
 * здесь то же самое проверяется на настоящем PostgreSQL 16, по ЗАКОММИЧЕННОМУ
 * состоянию (старт/завершение — отдельными транзакциями), плюс сам инвариант
 * утечек (WO-OPS-26/32) на тех же данных.
 */
public class CompletedInstanceSubscriptionCleanupPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ActivityService activityService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID startFixture(String file) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + file));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
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

    private void completeMainTask(UUID pi, String elementId) {
        activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /** Факт БД PostgreSQL: строки подписок инстанса (все, независимо от consumed). */
    private long subscriptionRows(UUID pi, String table) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE process_instance_id = ?", Long.class, pi);
        return n == null ? 0L : n;
    }

    private void assertCompletedNormally(UUID pi) {
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("инстанс завершился НОРМАЛЬНО").isNotNull();
        assertThat(instance.isCancelled()).as("инстанс НЕ отменён").isFalse();
    }

    /** Message-подписка event-subprocess: создана при старте, обязана исчезнуть с инстансом. */
    @Test
    void normallyCompletedInstance_dropsMessageSubscription() {
        UUID pi = startFixture("test-event-subprocess.bpmn");

        assertThat(subscriptionRows(pi, "message_subscriptions"))
            .as("премисса: event-subprocess подписался на message 'cancelOrder'")
            .isPositive();

        completeMainTask(pi, "mainTask");
        assertCompletedNormally(pi);

        assertThat(subscriptionRows(pi, "message_subscriptions"))
            .as("нормально завершённый инстанс не оставляет message-подписок")
            .isZero();
    }

    /** Signal-подписки: граница на хосте и signal-started event sub-process. */
    @Test
    void normallyCompletedInstance_dropsSignalSubscription() {
        UUID pi = startFixture("test-ops34-completed-subscriptions.bpmn");

        assertThat(subscriptionRows(pi, "signal_subscriptions"))
            .as("премисса: инстанс вооружён signal-подписками (граница + event-subprocess)")
            .isGreaterThanOrEqualTo(2L);

        completeMainTask(pi, "mainTask");
        assertCompletedNormally(pi);

        assertThat(subscriptionRows(pi, "signal_subscriptions"))
            .as("нормально завершённый инстанс не оставляет signal-подписок")
            .isZero();
    }

    /** Тот же факт — глазами инварианта утечек, на данных PostgreSQL. */
    @Test
    void normallyCompletedInstance_hasNoLeakInvariantSubscriptionViolation() {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);

        UUID pi = startFixture("test-event-subprocess.bpmn");
        completeMainTask(pi, "mainTask");
        assertCompletedNormally(pi);

        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbc, baseline);

        assertThat(result.own())
            .as("инвариант утечек на PostgreSQL: у завершённого инстанса нет остатка подписок; нарушения: %s",
                result.own())
            .filteredOn(v -> "subscription".equals(v.type()))
            .isEmpty();
    }

    /** Доставка после завершения: сообщение некому коррелировать. */
    @Test
    void messagePublishedAfterCompletion_doesNotReachTheDeadInstance() {
        UUID pi = startFixture("test-event-subprocess.bpmn");
        completeMainTask(pi, "mainTask");
        assertCompletedNormally(pi);

        int correlated = activityService.publishMessage("cancelOrder", null, pi, List.of()).getCorrelated();

        assertThat(correlated)
            .as("после нормального завершения подписки нет — сообщение некому коррелировать")
            .isZero();
    }
}
