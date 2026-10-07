package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
 * WO-C8-38 (C38-2): arrived-строки отменённого scope остаются в {@code parallel_gateways}
 * и через {@code hasParkedJoinReaching} удерживают СОСЕДНИЙ join.
 *
 * <p>После прерывающей отмены scope сам join отменённого scope стрелять не должен
 * (верно — хвост отменённого scope запускать нельзя, POF-2 раунда 4 C8-35), но его
 * arrived-строки обязаны умирать вместе со scope в ТОЙ ЖЕ транзакции отмены.
 * Иначе {@code getGatewaysWithOpenArrivals} возвращает мёртвый id, достижимость
 * {@code canReach(мёртвый join, соседний join)} по модели истинна (выход scope ведёт
 * в соседа), и соседний join ждёт доставщика, которого уже нет, — инстанс висит.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CancelledScopeArrivalCleanupIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityService activityService;
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
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    @Transactional
    @Test
    void deadArrivalOfCancelledScope_doesNotHoldTheNeighbourJoin() throws Exception {
        UUID pi = start("test-c838-dead-arrival-holds-neighbour-join.bpmn");

        // Шаг 1: h1 приходит в joinIn — arrived-строка записана, joinIn припаркован
        // (h2 ещё жив и достижим, joinIn ждать обязан).
        complete(pi, "h1");
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("premise: h1 arrival parks the inner join")
            .contains("joinIn");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("premise: the inner join waits for h2")
            .isEqualTo(0L);

        // Шаг 2: прерывающая граница гасит scope subProc. Сам joinIn стрелять НЕ должен
        // (хвост отменённого scope), но его arrived-строка обязана умереть в той же
        // транзакции отмены — иначе она будет держать joinMain вечно.
        ActivityEntity subProc = activities(pi).stream()
            .filter(a -> "subProc".equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no live subProc in " + pi));
        activityService.fireBoundaryTimer(subProc.getId(), "killBnd");
        assertThat(countOf(pi, "joinIn", ActivityStatus.COMPLETED))
            .as("the cancelled scope's join must never fire its tail")
            .isEqualTo(0L);
        assertThat(dbService.getGatewaysWithOpenArrivals(pi))
            .as("the cancelled scope's arrival rows die with the scope, in the same transaction")
            .doesNotContain("joinIn");

        // Шаг 3: оставшиеся ветви приходят в joinMain. subProc мёртв (его выход fSubAfter
        // недостижим из живых исполнений), а мёртвый joinIn больше не держит соседа —
        // joinMain срабатывает РОВНО ОДИН РАЗ.
        complete(pi, "taskA");
        complete(pi, "taskKill");
        assertThat(countOf(pi, "joinMain", ActivityStatus.COMPLETED))
            .as("nobody can deliver into joinMain any more — it must fire exactly once")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);

        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance must actually complete, not hang with a parked token")
            .isNotNull();
    }
}
