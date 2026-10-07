package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-38 (C38-2, раунд 2, БЛОКИРУЮЩАЯ №1): чистка arrived-строк join'ов отменённого
 * scope на четырёх путях отмены, которые раунд 1 не покрыл: error scope-walk
 * ({@code ErrorEscalationThrower.throwError}), interrupting-escalation
 * ({@code throwEscalation}), terminate in-scope ({@code TerminateEndEvent}) и
 * cancel-end транзакции ({@code CancelEndHandler}).
 *
 * <p>Форма каждого теста — та же, что мост P0-зонда красной команды и тест C38-2 раунда 1:
 * parked joinIn внутри отменяемого scope + boundary-ребро taskY --(bndY)--> joinMain
 * ({@code canReach(joinIn, joinMain) = TRUE} по модели). Тело scope ЛИНЕЙНО на scope-токене
 * (без форка внутри — форк отцепил бы токен, и token-scope-keyed пути стали бы недостижимы).
 * Сам joinIn стрелять НЕ должен (хвост отменённого scope, POF-2 раунда 4 C8-35).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CancelledScopeArrivalCleanupExtraPathsIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private DBService dbService;

    private UUID start(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        if (file.contains("cancelend")) {
            // cancel-фикстура несёт FEEL-скрипты (cTask/afterCancel) как в образце
            // test-transaction-cancel: переменная log обязана существовать на старте.
            ProcessVariable log = new ProcessVariable();
            log.setName("log");
            log.setType(ProcessVariableType.LONG);
            log.setValue("0");
            dto.setVariables(List.of(log));
        }
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
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

    private long incidents(UUID pi) {
        List<UUID> ids = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream().filter(i -> ids.contains(i.getActivityId())).count();
    }

    private void complete(UUID pi, String elementId) {
        activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /**
     * Общий сценарий: h1 паркует joinIn (h2in жив, ребро bTrig достижимо) → отмена scope
     * триггерной задачей → joinIn молчит + его строк нет → taskA/taskB → joinMain ровно 1 раз.
     *
     * @param triggerTask задача, чьё завершение запускает отмену scope (eTask/sTask/tTask/cTask)
     */
    private void parkedJoinCleanedOnScopeCancel(UUID pi, String triggerTask) {
        // Шаг 1: h1 приходит в joinIn — arrived-строка записана, joinIn припаркован
        // (h2in ещё жив и достижим через ребро непрерывающей границы bTrig).
        complete(pi, "h1");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: h1 arrival parks the inner join")
            .contains("joinIn");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("premise: the inner join waits for the live branch")
            .isEqualTo(0L);

        // Шаг 2: триггерная задача идёт к отмене scope. h2in НЕ завершаем: он —
        // живой доставщик, держащий joinIn припаркованным; умрёт он самой отменой.
        // (Завершить h2in значило бы исчерпать и bTrig-ребро: joinIn стал бы готов
        // и сработал бы ШТАТНО до отмены — чистить было бы нечего.)
        complete(pi, triggerTask);

        // Отмена scope: сам joinIn стрелять НЕ должен (хвост отменённого scope),
        // но его arrived-строка обязана умереть в той же транзакции отмены.
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("the cancelled scope's join must never fire its tail")
            .isEqualTo(0L);
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("the cancelled scope's arrival rows die with the scope, in the same transaction")
            .doesNotContain("joinIn");

        // Шаг 3: оставшиеся ветви приходят в joinMain — мёртвый joinIn больше не держит
        // соседа через мост taskY--(bndY)-->joinMain: joinMain срабатывает РОВНО ОДИН РАЗ.
        complete(pi, "taskA");
        complete(pi, "taskB");
        assertThat(countOf(pi, "joinMain", ActivityStatus.COMPLETED))
            .as("the dead row must not hold the neighbour join — it fires exactly once")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);

        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance must actually complete, not hang with a parked token")
            .isNotNull();
    }

    @Transactional
    @Test
    void errorScopeWalk_clearsTheCancelledScopeJoin() throws Exception {
        parkedJoinCleanedOnScopeCancel(
            start("test-c838-errwalk-holds-neighbour-join.bpmn"), "eTask");
    }

    @Transactional
    @Test
    void interruptingEscalation_clearsTheCancelledScopeJoin() throws Exception {
        parkedJoinCleanedOnScopeCancel(
            start("test-c838-escalate-holds-neighbour-join.bpmn"), "sTask");
    }

    @Transactional
    @Test
    void terminateInScope_clearsTheCancelledScopeJoin() throws Exception {
        parkedJoinCleanedOnScopeCancel(
            start("test-c838-terminate-holds-neighbour-join.bpmn"), "tTask");
    }

    @Transactional
    @Test
    void cancelEnd_clearsTheCancelledTransactionJoin() throws Exception {
        UUID pi = start("test-c838-cancelend-holds-neighbour-join.bpmn");

        complete(pi, "h1");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: h1 arrival parks the inner join")
            .contains("joinIn");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("premise: the inner join waits for the live branch")
            .isEqualTo(0L);

        // Cancel-end гасит транзакцию (h2in умирает отменой, не завершением — см. выше).
        // cTask/afterCancel — FEEL-скрипты (не userTask'и): срабатывают сами на старте
        // волны, complete() им не нужен — ждём только их следствия.

        // Cancel-end гасит транзакцию и продолжает cancel-границей (компенсаций нет —
        // runCompensation no-op): joinIn молчит, его строк нет.
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("the cancelled transaction's join must never fire its tail")
            .isEqualTo(0L);
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("the cancelled transaction's arrival rows die with the scope, in the same transaction")
            .doesNotContain("joinIn");
        assertThat(countOf(pi, "afterCancel", ActivityStatus.COMPLETED))
            .as("premise: the cancel boundary continuation ran")
            .isEqualTo(1L);

        complete(pi, "taskA");
        complete(pi, "taskB");
        assertThat(countOf(pi, "joinMain", ActivityStatus.COMPLETED))
            .as("the dead row must not hold the neighbour join — it fires exactly once")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);

        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance must actually complete, not hang with a parked token")
            .isNotNull();
    }
}
