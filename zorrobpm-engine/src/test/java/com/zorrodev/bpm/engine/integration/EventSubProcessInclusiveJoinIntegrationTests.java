package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
 * WO-C8-35 раунд 4 (Решение CTO 1 и 2) — event sub-process и inclusive-join.
 *
 * <p>Три сценария, по которым CTO зафиксировал развилки:
 * <ul>
 *   <li><b>(a)</b> непрерывающий armed event-subprocess + join в РОДИТЕЛЬСКОМ scope → join
 *       срабатывает после доставки всех ветвей, а не висит вечно из-за вечной подписки;</li>
 *   <li><b>(b)</b> join ВНУТРИ event-subprocess не ждёт собственный стартовый триггер;</li>
 *   <li><b>(c)</b> прерывающий event-subprocess, под которым висит join.</li>
 * </ul>
 *
 * <p><b>(c) — контрпример, а не «ещё один фикс».</b> Находка @verifier раунда 3 требовала
 * перепроверки припаркованных join'ов на cancel-пути прерывающего event-subprocess
 * ({@code EventTrigger.triggerEventSubprocess}). Правка была сделана «на глаз» и откачена
 * (@verifier, sha {@code 14f5d71a}: {@code getToken(null)} → падение, прерывающий
 * event-sub-process не стартует). В раунде 4 CTO велел вернуть её с настоящим токеном
 * (Решение 1) — и это правило проверяется здесь на живом прогоне, а не на умом:
 * {@code cancelActiveActivities} гасит activity ВСЕГО инстанса, а прерывающий event-subprocess
 * по BPMN ЗАМЕЩАЕТ основной поток (его собственный end event завершает инстанс). Значит
 * воскрешение join'а из отменённого scope не «чинит висящий join», а ЗАПУСКАЕТ хвост
 * отменённого потока. Этот тест — тот самый RED-контрпример: он зелёный на текущем дереве и
 * падает ровно на той правке, которую предлагалось вернуть (мутация — в отчёте, §14.16).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class EventSubProcessInclusiveJoinIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ActivityService activityService;

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
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    private long incidents(UUID pi) {
        var activityIds = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
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
     * Решение CTO 2, тест (c): прерывающий event-subprocess под паркованным join'ом.
     *
     * <p>Диаграмма {@code test-c835-evsub-incl-join.bpmn}: main-flow форк, ветвь {@code taskMain}
     * приходит в inclusive-join и ПАРКУЕТСЯ (второй ветви {@code taskWait → taskHold} ещё надо
     * дойти), затем сообщение {@code interruptNow} поднимает ПРЕРЫВАЮЩИЙ event-subprocess.
     *
     * <p>Ожидание — по BPMN, а не по «как удобно»: прерывание ОТМЕНЯЕТ основной поток и
     * event-subprocess ЗАМЕЩАЕТ его, поэтому хвост отменённого потока запускаться НЕ должен, а
     * инстанс завершается end event'ом самого handler'а. Join при этом остаётся паркованным
     * навсегда — и это нормально: отменённый scope никому ничего не должен.
     */
    @Transactional
    @Test
    void interruptingEventSubProcess_completesTheInstanceAndDoesNotRunTheCancelledTail() throws Exception {
        UUID pi = start("test-c835-evsub-incl-join.bpmn");

        complete(pi, "taskMain");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskWait is still live and can reach the join — the branch must park")
            .isEqualTo(0L);

        activityService.correlateMessage("interruptNow", pi, List.of());

        assertThat(countOf(pi, "taskWait", ActivityStatus.CANCELLED))
            .as("an interrupting event sub-process cancels the main flow")
            .isEqualTo(1L);
        assertThat(countOf(pi, "evEnd", ActivityStatus.COMPLETED))
            .as("the handler ran to its own end — it replaces the main flow, it does not merge with it")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("the tail of a CANCELLED scope must never run: waking the parked join here would "
                + "execute the interrupted main flow's downstream — this is the counterexample to "
                + "re-adding resumeParkedInclusiveJoins on this path")
            .isEqualTo(0L);
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the join belongs to the cancelled scope — it stays parked, it does not fire")
            .isEqualTo(0L);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance completes through the handler's end event — a parked join in the "
                + "cancelled scope does NOT hang it (this is what makes the re-check unnecessary here)")
            .isNotNull();
        assertThat(incidents(pi))
            .as("no incident: the round-3-bis attempt produced one and blocked the handler's start")
            .isEqualTo(0L);
    }
}