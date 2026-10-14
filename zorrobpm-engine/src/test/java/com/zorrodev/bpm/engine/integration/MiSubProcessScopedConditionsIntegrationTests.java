package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-45: условия внутри MI-инстанса/подпроцесса видят переменные скоупа
 * (второй дефект консьюмера 2026-10-14, инстанс {@code 34ce6c0a}: gateway
 * {@code gwLaneVerdict} с {@code =decision = "REJECTED"} давал оба потока false
 * и инцидент, потому что условия оценивались только по root-переменным).
 *
 * <p>Фикстуры — минимальные процессы по мотивам реального v2 консьюмера:
 * внешних воркеров нет, service task'и завершаются через
 * {@code runtimeService.completeServiceTask} (тот же путь, что worker-callback).
 * Ветки различаются assignee {@code =item} (A/B), поэтому перекрёст исключён
 * конструкцией: каждая ветка обязана пойти по СВОЕМУ решению.
 *
 * <p>LEAK-INVARIANT (G-P): каждый тест, доводящий инстанс до конца, проверяет —
 * нет activity в CREATED/IN_PROGRESS, инстанс завершён, инцидентов 0.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessScopedConditionsIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private UserTaskRepository userTaskRepository;
    @Autowired
    private DBService dbService;
    @Autowired
    private FlowNavigator flowNavigator;

    private UUID deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        return processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private long liveActivities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .count();
    }

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private String assigneeOf(UUID activityId) {
        return userTaskRepository.findById(activityId).map(u -> u.getAssignee()).orElse(null);
    }

    private UUID userTaskByAssignee(UUID pi, String bpmnElementId, String assignee) {
        List<ActivityEntity> found = activities(pi, bpmnElementId, ActivityStatus.CREATED).stream()
            .filter(a -> assignee.equals(assigneeOf(a.getId())))
            .toList();
        assertThat(found).as(bpmnElementId + " of instance " + assignee).hasSize(1);
        return found.get(0).getId();
    }

    private static ProcessVariable pv(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    /**
     * WO-C8-45 (а): exclusive gateway внутри MI-подпроцесса ветвится по
     * completion-переменной {@code decision} СВОЕГО инстанса (A=REJECTED,
     * B=APPROVED). На root-чтении оба условия false → инцидент и ни одной
     * веточной задачи (RED); на chain-чтении — каждый по своей ветке (GREEN).
     */
    @Transactional
    @Test
    void exclusiveGatewayInsideMiSubprocess_routesByOwnInstanceDecision() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-gateway-decision.bpmn"));

        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "A"),
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "B"),
            List.of(pv("decision", ProcessVariableType.STRING, "APPROVED")));

        List<ActivityEntity> marks = activities(pi, "svcMark", ActivityStatus.CREATED);
        assertThat(marks).as("svcMark per instance").hasSize(2);
        marks.forEach(m -> runtimeService.completeServiceTask(m.getId(), List.of()));

        List<ActivityEntity> rejected = activities(pi, "utRejected", ActivityStatus.CREATED);
        assertThat(rejected).as("REJECTED branch of instance A").hasSize(1);
        assertThat(assigneeOf(rejected.get(0).getId())).as("rejected branch runs in instance A").isEqualTo("A");
        List<ActivityEntity> approved = activities(pi, "utApproved", ActivityStatus.CREATED);
        assertThat(approved).as("APPROVED branch of instance B").hasSize(1);
        assertThat(assigneeOf(approved.get(0).getId())).as("approved branch runs in instance B").isEqualTo("B");
        assertThat(incidents(pi)).as("no incidents").isZero();

        runtimeService.completeUserTask(rejected.get(0).getId(), List.of());
        runtimeService.completeUserTask(approved.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    /**
     * WO-C8-45 (а, вариант laneDecision): то же, но условие читает
     * спроецированную {@code laneDecision} (output svcMark), а не сырой decision.
     * Проверяет, что overlay-выход виден условию в скоупе инстанса.
     */
    @Transactional
    @Test
    void exclusiveGatewayInsideMiSubprocess_routesByProjectedLaneDecision() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-gateway-lane.bpmn"));

        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "A"),
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "B"),
            List.of(pv("decision", ProcessVariableType.STRING, "APPROVED")));

        List<ActivityEntity> marks = activities(pi, "svcMark", ActivityStatus.CREATED);
        assertThat(marks).as("svcMark per instance").hasSize(2);
        marks.forEach(m -> runtimeService.completeServiceTask(m.getId(), List.of()));

        List<ActivityEntity> rejected = activities(pi, "utRejected", ActivityStatus.CREATED);
        assertThat(rejected).as("REJECTED branch of instance A").hasSize(1);
        assertThat(assigneeOf(rejected.get(0).getId())).as("rejected branch runs in instance A").isEqualTo("A");
        List<ActivityEntity> approved = activities(pi, "utApproved", ActivityStatus.CREATED);
        assertThat(approved).as("APPROVED branch of instance B").hasSize(1);
        assertThat(assigneeOf(approved.get(0).getId())).as("approved branch runs in instance B").isEqualTo("B");
        assertThat(incidents(pi)).as("no incidents").isZero();

        runtimeService.completeUserTask(rejected.get(0).getId(), List.of());
        runtimeService.completeUserTask(approved.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    /**
     * WO-C8-45 (б): inclusive gateway-СПЛИТ внутри MI-подпроцесса активирует
     * ровно одну ветвь на инстанс по его decision. На root-чтении activated
     * пусто (RED: веточных задач нет вообще).
     */
    @Transactional
    @Test
    void inclusiveSplitInsideMiSubprocess_activatesBranchByOwnInstanceDecision() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-inclusive-scope.bpmn"));

        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "A"),
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "B"),
            List.of(pv("decision", ProcessVariableType.STRING, "APPROVED")));

        List<ActivityEntity> rejected = activities(pi, "utIncRejected", ActivityStatus.CREATED);
        assertThat(rejected).as("REJECTED branch of instance A").hasSize(1);
        assertThat(assigneeOf(rejected.get(0).getId())).as("rejected branch runs in instance A").isEqualTo("A");
        List<ActivityEntity> approved = activities(pi, "utIncApproved", ActivityStatus.CREATED);
        assertThat(approved).as("APPROVED branch of instance B").hasSize(1);
        assertThat(assigneeOf(approved.get(0).getId())).as("approved branch runs in instance B").isEqualTo("B");
        assertThat(incidents(pi)).as("no incidents").isZero();

        runtimeService.completeUserTask(rejected.get(0).getId(), List.of());
        runtimeService.completeUserTask(approved.get(0).getId(), List.of());

        // Каждая веточная задача сдана, оба инстанса дошли до своих end:
        // контейнеры COMPLETED (не orphan), инстанс завершён.
        // LEAK-INVARIANT полный: живых нет, инцидентов нет.
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completed after both split branches").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    /**
     * WO-C8-45 (в): ОБЫЧНЫЙ подпроцесс со скоуп-локальной переменной
     * (input mapping контейнера). Gateway внутри видит её только через chain:
     * в root localFlag нет вообще (RED: инцидент), со скоупом — utYes (GREEN).
     */
    @Transactional
    @Test
    void exclusiveGatewayInsidePlainSubprocess_seesContainerInputVariable() throws Exception {
        UUID pi = start(deploy("test-c845-plain-sub-gateway-scope.bpmn"));

        assertThat(activities(pi, "utInner", ActivityStatus.CREATED)).as("inner task parked").hasSize(1);
        runtimeService.completeUserTask(
            activities(pi, "utInner", ActivityStatus.CREATED).get(0).getId(), List.of());

        List<ActivityEntity> yes = activities(pi, "utYes", ActivityStatus.CREATED);
        assertThat(yes).as("gateway took the localFlag=true branch").hasSize(1);
        assertThat(activities(pi, "utNo", ActivityStatus.CREATED)).as("false branch not taken").isEmpty();
        assertThat(incidents(pi)).as("no incidents").isZero();

        runtimeService.completeUserTask(yes.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    /**
     * WO-C8-45 (г): вложенный MI (MI-подпроцесс в обычном подпроцессе) с
     * completionCondition по results. Агрегация F3 пишет results в containing
     * scope (скоуп subOuter ≠ root): на root-чтении условие не срабатывает —
     * после сдачи b-ветки utAfter НЕТ и инстанс ждёт вторую (RED); на
     * chain-чтении — ранний выход (GREEN): utAfter создан, сиблинг погашен.
     */
    @Transactional
    @Test
    void nestedMiCompletionCondition_seesOutputCollectionInContainingScope() throws Exception {
        UUID pi = start(deploy("test-c845-nested-mi-completion-condition.bpmn"));

        assertThat(activities(pi, "utInner", ActivityStatus.CREATED)).as("2 parallel inner tasks").hasSize(2);
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utInner", "b"), List.of());

        List<ActivityEntity> after = activities(pi, "utAfter", ActivityStatus.CREATED);
        assertThat(after).as("early exit: utAfter created after the b-branch (completionCondition fired)").hasSize(1);
        assertThat(activities(pi, "utInner", ActivityStatus.CREATED))
            .as("remaining sibling terminated by early exit (F4)").isEmpty();
        assertThat(incidents(pi)).as("no incidents").isZero();

        runtimeService.completeUserTask(after.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    /**
     * WO-C8-45 (д): условие на ПРОСТОМ sequenceFlow после задачи внутри
     * MI-инстанса (не у гейтвея) — белый ящик через прод-путь
     * {@code FlowNavigator.processFlow}. decision пишется НАПРЯМУЮ в скоуп
     * инстанса; flowApproved обязан дать null, flowRejected — взят (non-null).
     * На root-чтении ОБА null (RED).
     */
    @Transactional
    @Test
    void plainSequenceFlowConditionInsideMiInstance_seesInstanceScope() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-gateway-decision.bpmn"));

        UUID decideA = userTaskByAssignee(pi, "utDecide", "A");
        com.zorrodev.bpm.engine.dto.Activity live = dbService.getActivity(decideA);
        UUID innerToken = live.getToken();
        assertThat(innerToken).as("inner task runs on a token").isNotNull();
        UUID instanceScope = dbService.findToken(innerToken)
            .map(com.zorrodev.bpm.engine.dto.Token::getScopeActivityId)
            .orElseThrow(() -> new AssertionError("inner task must run on a scope token"));
        dbService.setVariables(pi, instanceScope,
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));

        assertThat(flowNavigator.processFlow(pi, innerToken, "flowApproved", true, null))
            .as("APPROVED flow not taken for REJECTED decision").isNull();
        assertThat(flowNavigator.processFlow(pi, innerToken, "flowRejected", true, null))
            .as("REJECTED flow taken for REJECTED decision").isNotNull();
    }

    /**
     * WO-C8-45 (е): conditional-CATCH внутри обычного подпроцесса видит
     * скоуп-локальную переменную контейнера. На root-чтении условие ложно →
     * токен паркуется на condCatch (RED: utSecond нет); на chain-чтении —
     * проход на входе (GREEN: utSecond создан сразу после сдачи utFirst).
     */
    @Transactional
    @Test
    void conditionalCatchInsidePlainSubprocess_seesContainerInputVariable() throws Exception {
        UUID pi = start(deploy("test-c845-conditional-in-subscope.bpmn"));

        assertThat(activities(pi, "utFirst", ActivityStatus.CREATED)).as("first task parked").hasSize(1);
        runtimeService.completeUserTask(
            activities(pi, "utFirst", ActivityStatus.CREATED).get(0).getId(), List.of());

        List<ActivityEntity> second = activities(pi, "utSecond", ActivityStatus.CREATED);
        assertThat(second).as("conditional catch passed through on container-local localFlag").hasSize(1);
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * WO-C8-45 (ж): не прерывающая conditional-BOUNDARY внутри подпроцесса
     * срабатывает при перепроверке из {@code triggerConditionalEvents} после
     * завершения соседней задачи. На root-чтении ветки нет (RED); на
     * chain-чтении utBranch создан, хост utSecond жив (GREEN). Продолжает (е):
     * доводит инстанс до конца с LEAK-INVARIANT.
     */
    @Transactional
    @Test
    void conditionalBoundaryInsideSubscope_firesOnReevaluationAfterSiblingCompletion() throws Exception {
        UUID pi = start(deploy("test-c845-conditional-in-subscope.bpmn"));

        runtimeService.completeUserTask(
            activities(pi, "utFirst", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(activities(pi, "utSecond", ActivityStatus.CREATED))
            .as("catch passed, second task parked").hasSize(1);

        // Перепроверка conditional-событий уже отработала на хвосте завершения
        // utFirst: граница должна выстрелить по скоуп-локальному localFlag.
        List<ActivityEntity> branch = activities(pi, "utBranch", ActivityStatus.CREATED);
        assertThat(branch).as("non-interrupting conditional boundary fired on scope-local flag").hasSize(1);
        assertThat(activities(pi, "utSecond", ActivityStatus.CREATED))
            .as("non-interrupting boundary leaves the host alive").hasSize(1);
        assertThat(incidents(pi)).as("no incidents").isZero();

        // Не прерывающая граница с вечно-истинным условием выстрелила бы
        // ЗАНОВО на хвосте сдачи utBranch (хост utSecond ещё жив) — вторая
        // ветвь осталась бы CREATED навсегда. Гасим условие ПЕРЕД сдачей
        // ветви записью localFlag=false в скоуп контейнера: заодно негативная
        // половина (условие ложно → перепроверка ветвь не создаёт).
        UUID hostToken = dbService.getActivity(
            activities(pi, "utSecond", ActivityStatus.CREATED).get(0).getId()).getToken();
        UUID containerScope = dbService.findToken(hostToken)
            .map(com.zorrodev.bpm.engine.dto.Token::getScopeActivityId)
            .orElseThrow(() -> new AssertionError("host must run on a scope token"));
        dbService.setVariables(pi, containerScope,
            List.of(pv("localFlag", ProcessVariableType.BOOLEAN, "false")));

        runtimeService.completeUserTask(branch.get(0).getId(), List.of());
        assertThat(activities(pi, "utBranch", ActivityStatus.CREATED))
            .as("no replacement branch after the condition turned false").isEmpty();
        runtimeService.completeUserTask(
            activities(pi, "utSecond", ActivityStatus.CREATED).get(0).getId(), List.of());
        runtimeService.completeUserTask(
            activities(pi, "utAfter", ActivityStatus.CREATED).get(0).getId(), List.of());

        // Обе ветви сданы (utBranch и utSecond) — scope закрыт, utAfter создан.
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertThat(liveActivities(pi)).as("LEAK-INVARIANT: no live activities left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }
}
