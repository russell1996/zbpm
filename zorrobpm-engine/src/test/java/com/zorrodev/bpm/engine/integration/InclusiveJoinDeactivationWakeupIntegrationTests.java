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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 5, ШАГ 2 (BLOCKER-4 red-team, живой прогон): «последний возможный доставщик»
 * умер на пути ERROR/ESCALATION — припаркованный inclusive-join не просыпался НИКОГДА.
 *
 * <p>Перепроверка припаркованных join'ов стояла вручную на шести точках деактивации, и ни одна
 * из них не была в {@code ErrorEscalationThrower}. Прерывающая ERROR-граница на живом хосте
 * делает ровно то, ради чего перепроверка существует: хост умирает (это был последний
 * возможный доставщик), а никто правило не перечитывает.
 *
 * <p>Диаграммы — дословно из рецензии red-team (WO-C8-35-independent-review-r3.md §BLOCKER-4):
 * ERROR — {@code rt3-errbnd-stranded-join.bpmn}, ESCALATION — та же форма на call activity.
 * Наблюдение red-team, которое здесь воспроизводится как RED: join COMPLETED=0,
 * taskAfter CREATED=0, {@code completedAt=null}, инцидентов 0 — инстанс висит RUNNING.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InclusiveJoinDeactivationWakeupIntegrationTests {

    private static final AtomicLong UNIQ = new AtomicLong();

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

    private String bpmn(String file) throws Exception {
        return Files.readString(Paths.get("src/test/files/" + file));
    }

    private ProcessVariable var(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
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

    /** CREATED-строка элемента этого инстанса (для completeUserTask / throwServiceTaskError). */
    private ActivityEntity active(UUID pi, String elementId) {
        return activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED " + elementId + " in " + pi));
    }

    private long incidents(UUID pi) {
        List<UUID> ids = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream()
            .filter(i -> ids.contains(i.getActivityId()))
            .count();
    }

    private void complete(UUID pi, String elementId) {
        runtimeService.completeUserTask(active(pi, elementId).getId(), List.of());
    }

    // ── ERROR: прерывающая error-граница на ПОСЛЕДНЕМ доставщике ───────────────────────────
    @Transactional
    @Test
    void interruptingErrorBoundaryOnTheLastDeliverer_wakesTheParkedJoin() throws Exception {
        UUID pdId = processDefinitionService.addProcessDefinition(
            bpmn("test-c835-errbnd-stranded-join.bpmn")).getId();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(var("svcOk", "yes")));
        IdDTO started = runtimeService.startProcessInstance(dto);
        UUID pi = started.getId();

        // taskA arrives at the join; taskSvc is still live and CAN reach the join (through
        // xorSvc's true branch) — the join parks, correctly.
        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskSvc is live and can still reach the join — the join must wait")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(0L);

        // The error kills the host on the OTHER branch — the last possible deliverer.
        activityService.throwServiceTaskError(active(pi, "taskSvc").getId(), "E-THROW", List.of());

        assertThat(countOf(pi, "taskSvc", ActivityStatus.CANCELLED))
            .as("premise: the error boundary interrupted its host")
            .isEqualTo(1L);
        assertThat(countOf(pi, "escapeEnd", ActivityStatus.COMPLETED))
            .as("premise: the error branch really ran")
            .isEqualTo(1L);
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("nobody in the instance can reach the join any more — it must fire, not sleep forever")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("downstream of the join must be entered (red-team: taskAfter CREATED=0, инстанс RUNNING)")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);

        // …и хвост реально доходит до конца, а не просто «join не уснул»
        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
    }

    // ── ESCALATION: то же самое на escalation-семействе (call activity в родителе) ──────────
    @Transactional
    @Test
    void interruptingEscalationBoundaryOnTheCallActivity_wakesTheParentJoin() throws Exception {
        String childKey = "c835escch" + UNIQ.incrementAndGet();
        processDefinitionService.addProcessDefinition(
            bpmn("test-c835-escbnd-stranded-join-child.bpmn")
                .replace("test-c835-escbnd-stranded-join-child", childKey));
        UUID pdId = processDefinitionService.addProcessDefinition(
            bpmn("test-c835-escbnd-stranded-join-parent.bpmn")
                .replace("CHILD_KEY", childKey)).getId();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("callSub is live and can still reach the join — the join must wait")
            .isEqualTo(0L);

        // The child throws ESC-1; it propagates to the call activity in the PARENT and the
        // interrupting escalation boundary there cancels that call-activity row.
        ActivityEntity childTask = activityRepository.findAll().stream()
            .filter(a -> a.getBpmnElementId().equals("cTask"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the child instance is not parked on cTask"));
        runtimeService.completeUserTask(childTask.getId(), List.of());

        assertThat(countOf(pi, "callSub", ActivityStatus.CANCELLED))
            .as("premise: the escalation boundary interrupted the call activity in the parent")
            .isEqualTo(1L);
        assertThat(countOf(pi, "escapeEnd", ActivityStatus.COMPLETED))
            .as("premise: the escalation branch really ran")
            .isEqualTo(1L);
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the parent's parked join must wake when its last deliverer dies")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }
}