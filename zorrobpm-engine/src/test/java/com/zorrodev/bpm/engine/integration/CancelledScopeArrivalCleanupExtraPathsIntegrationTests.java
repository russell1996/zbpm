package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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
 * WO-C8-38 (C38-2, раунд 2, решение CTO по эскалации V10): живое подтверждение
 * теоремы недостижимости для четырёх error/escalate/terminate-путей отмены scope.
 *
 * <p>Теорема (§8 отчёта): scope-ветки отмены (error scope-walk, interrupting-escalation,
 * terminate in-scope) входят только на токене с {@code scopeActivityId}, а такой токен
 * один — живая парковка join и позиция триггера на нём несовместны. Поэтому чистка
 * arrived-строк на этих сайтах — defense-in-depth (её ВЫЗОВ доказывает
 * {@code ScopeJoinCleanupWiringTest} 4/4 с POF-мутациями), а сквозной «RED без чистки»
 * здесь невозможен: к моменту триггера join уже сработал ШТАТНО через резюм
 * деактивации доставщика, чистить нечего и не вредит.
 *
 * <p>Форма каждого теста: h1 паркует joinIn → завершение h2in срабатывает joinIn
 * штатно ДО отмены (ровно 1 раз, taskY создан, open-строк joinIn больше нет) →
 * триггер отмены идёт своим путём → joinIn по-прежнему ровно 1 (без второго
 * срабатывания и без хвоста отменённого потока), соседний joinMain — ровно 1 раз.
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
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /**
     * Общее ядро: h1 паркует joinIn → h2in срабатывает joinIn штатно ДО отмены →
     * joinIn ровно 1 раз, taskY создан, open-строк joinIn нет (чистить нечего).
     */
    private void parkedJoinFiresBeforeScopeCancel(UUID pi) {
        complete(pi, "h1");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: h1 arrival parks the inner join")
            .contains("joinIn");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("premise: the inner join waits for the live branch")
            .isEqualTo(0L);

        // Деактивация живого доставщика: join готов и срабатывает ШТАТНО через
        // резюм — до любой отмены. Чистка отменных путей здесь уже нечего чистить.
        complete(pi, "h2in");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("the join fires normally when its last deliverer completes, before any cancel")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskY", ActivityStatus.CREATED))
            .as("the join tail runs exactly once")
            .isEqualTo(1L);
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("no open arrival rows of the join are left for the cancel paths to clean")
            .doesNotContain("joinIn");
    }

    /**
     * C38-2-наблюдение после триггера отмены: путь отмены не оставил мёртвых
     * arrived-строк joinIn (чистить нечего — чистка defense-in-depth безвредна).
     */
    private void noDeadArrivalRowsAfterCancelTrigger(UUID pi) {
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("the cancel path left no dead arrival rows of the already-fired join")
            .doesNotContain("joinIn");
    }

    @Transactional
    @Test
    void errorScopeWalk_errorOnForkTokenLeavesScopeAlive_joinFiresBeforeCancel() throws Exception {
        UUID pi = start("test-c838-errwalk-holds-neighbour-join.bpmn");
        parkedJoinFiresBeforeScopeCancel(pi);

        // Триггер идёт на форк-токене без scopeActivityId (теорема §8): scope-walk
        // его не видит — ошибка необработана (видимый инцидент, не тихое висение),
        // scope жив, joinIn по-прежнему ровно 1 (без второго срабатывания).
        complete(pi, "eTask");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("no tail of a cancelled scope fires: the join fired once, before the cancel")
            .isEqualTo(1L);
        assertThat(countOf(pi, "subProc", ActivityStatus.CANCELLED))
            .as("the scope survives: a fork-token error does not cancel it")
            .isEqualTo(0L);
        assertThat(incidents(pi))
            .as("unhandled error is a visible incident, not a silent hang")
            .isEqualTo(1L);

        noDeadArrivalRowsAfterCancelTrigger(pi);
    }

    @Transactional
    @Test
    void interruptingEscalationOnForkToken_endsBranch_joinFiresBeforeCancel() throws Exception {
        UUID pi = start("test-c838-escalate-holds-neighbour-join.bpmn");
        parkedJoinFiresBeforeScopeCancel(pi);

        // Триггер идёт на форк-токене без scopeActivityId (теорема §8): прерывание
        // scope не срабатывает — ветвь триггера просто завершается, scope жив,
        // joinIn по-прежнему ровно 1, инцидента нет.
        complete(pi, "sTask");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("no tail of a cancelled scope fires: the join fired once, before the cancel")
            .isEqualTo(1L);
        assertThat(countOf(pi, "subProc", ActivityStatus.CANCELLED))
            .as("the scope survives: a fork-token escalation does not cancel it")
            .isEqualTo(0L);
        assertThat(incidents(pi)).isEqualTo(0L);

        noDeadArrivalRowsAfterCancelTrigger(pi);
    }

    @Transactional
    @Test
    void terminateOnForkToken_cancelsWholeInstance_singleJoinFiring() throws Exception {
        UUID pi = start("test-c838-terminate-holds-neighbour-join.bpmn");
        parkedJoinFiresBeforeScopeCancel(pi);

        // Триггер идёт на форк-токене без scopeActivityId (теорема §8 + V7-мостик
        // красной команды R4): in-scope ветка terminate недостижима, срабатывает
        // whole-instance ветка — инстанс завершён, joinIn по-прежнему ровно 1
        // (без второго срабатывания и без хвоста).
        complete(pi, "tTask");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("the join fired exactly once, before the terminate — never twice")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the whole-instance terminate branch completes the instance")
            .isNotNull();
    }
}
