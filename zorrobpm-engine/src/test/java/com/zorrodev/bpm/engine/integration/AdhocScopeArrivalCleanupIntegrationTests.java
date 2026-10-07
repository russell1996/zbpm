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
 * WO-C8-38 (C38-2, раунд 3, БЛОКИРУЮЩАЯ №1 рецензии r3): сквозные IT на живые
 * ad-hoc-сценарии — опровержение теоремы недостижимости живым путём.
 *
 * <p>Ad-hoc S2 даёт три concurrent-позиции на ОДНОМ scope-токене (внутренние корни
 * диспатчатся на scope-токене, форка внутри нет): A приходит в joinIn (парковка,
 * B жив-доставщик), а E бросает триггер на том же scope-токене — scope-ветка
 * отмены (error scope-walk / interrupting-escalation / terminate in-scope /
 * cancel-end) ВХОДИТ живым путём. Без чистки arrived-строк мёртвая строка joinIn
 * держит joinMain через мост (joinIn→taskIn—(bndH)→fBnd→joinMain) + стреляет хвост
 * отменённого scope (joinIn=1): joinMain=0, taskAfter=0, incidents=0 — HANG.
 * С чисткой (та же транзакция отмены, без resume): joinMain=1, taskAfter=1.
 *
 * <p>Форма каждого теста: complete taskY (joinMain паркуется) → complete A (joinIn
 * паркуется, B жив) → complete E (бросок: отмена S2 + чистка) → соседний join
 * срабатывает ровно один раз, хвоста отменённого scope нет, инцидента нет.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class AdhocScopeArrivalCleanupIntegrationTests {

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
     * Общее ядро: taskY паркует joinMain → A паркует joinIn (B жив-доставщик).
     */
    private void parkBothJoins(UUID pi) {
        complete(pi, "taskY");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: taskY arrival parks the neighbour join (ad-hoc scope alive)")
            .contains("joinMain");
        assertThat(countOf(pi, "joinMain", ActivityStatus.COMPLETED))
            .as("premise: the neighbour join waits while the scope can still deliver")
            .isEqualTo(0L);

        complete(pi, "A");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: A arrival parks the inner join (B alive on the same scope token)")
            .contains("joinIn");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("premise: the inner join waits for the live deliverer B")
            .isEqualTo(0L);
    }

    /**
     * Общее ядро проверок FIX после броска E: scope отменён, хвоста нет, сосед
     * сработал ровно один раз, инцидента нет, инстанс завершается штатно.
     */
    private void neighbourFiresOnceAfterAdhocCancel(UUID pi) {
        assertThat(countOf(pi, "S2", ActivityStatus.CANCELLED))
            .as("the cancel path entered the scope branch: the ad-hoc scope is cancelled")
            .isEqualTo(1L);
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("no tail of the cancelled scope fires")
            .isEqualTo(0L);
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("the cancelled scope's arrival rows die with the scope, in the same transaction")
            .doesNotContain("joinIn");
        assertThat(countOf(pi, "joinMain", ActivityStatus.COMPLETED))
            .as("the neighbour join fires exactly once — no dead row holds it any more")
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
    void errorScopeWalk_cancelsAdhocScopeWithParkedJoin_neighbourFiresOnce() throws Exception {
        UUID pi = start("test-c838-adhoc-errwalk-holds-neighbour-join.bpmn");
        parkBothJoins(pi);

        // E бросает E-1 на scope-токене: error scope-walk входит, S2 отменён,
        // arrived-строки joinIn чистятся в той же транзакции (без resume).
        complete(pi, "E");
        neighbourFiresOnceAfterAdhocCancel(pi);
    }

    @Transactional
    @Test
    void interruptingEscalation_cancelsAdhocScopeWithParkedJoin_neighbourFiresOnce() throws Exception {
        UUID pi = start("test-c838-adhoc-escalate-holds-neighbour-join.bpmn");
        parkBothJoins(pi);

        // E бросает ESC-1 на scope-токене: interrupting-escalation walk входит,
        // S2 отменён, arrived-строки joinIn чистятся в той же транзакции.
        complete(pi, "E");
        neighbourFiresOnceAfterAdhocCancel(pi);
    }

    @Transactional
    @Test
    void terminateInScope_cancelsAdhocScopeWithParkedJoin_neighbourFiresOnce() throws Exception {
        UUID pi = start("test-c838-adhoc-terminate-holds-neighbour-join.bpmn");
        parkBothJoins(pi);

        // E доходит до termEnd на scope-токене: in-scope ветка terminate входит
        // (отмена scope + чистка + продолжение с fSubAfter).
        complete(pi, "E");
        neighbourFiresOnceAfterAdhocCancel(pi);
    }

    @Transactional
    @Test
    void cancelEnd_cancelsAdhocScopeWithParkedJoin_neighbourFiresOnce() throws Exception {
        UUID pi = start("test-c838-adhoc-cancelend-holds-neighbour-join.bpmn");
        parkBothJoins(pi);

        // E доходит до cancelEnd на scope-токене: ad-hoc принят как transaction,
        // scope гасится + чистка + продолжение с cancel-границы S2.
        complete(pi, "E");
        neighbourFiresOnceAfterAdhocCancel(pi);
    }
}
