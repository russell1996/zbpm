package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-40 — multi-instance {@code subProcess} выполняется как одиночный (тихо).
 *
 * <p>RED-база: до фикса деплой любой из этих моделей падает
 * {@code UNSUPPORTED_MULTI_INSTANCE_CONTAINER} (WO-ENG-34) — движок MI на
 * подпроцессе не выполняет вовсе. Каждый тест ниже требует живого MI-поведения.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private com.zorrodev.bpm.engine.service.ActivityService activityService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId, List<ProcessVariable> variables) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
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

    private int loopCounterOf(UUID pi, UUID activityId) {
        return variableRepository.findByProcessInstanceIdAndScopeId(pi, activityId).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> Integer.parseInt(v.getTextValue()))
            .orElseThrow(() -> new AssertionError("No scoped loopCounter on " + activityId));
    }

    /**
     * Слот MI-инстанса подпроцесса для внутренней задачи: {@code loopCounter}
     * привязан на скоупе КОНТЕЙНЕРА-инстанса, не задачи — контейнер находим
     * через scope-токен задачи (scopeActivityId её токена). Для строк САМОГО
     * контейнера (create-джобы спавнятся на входящем токене без scope) —
     * собственный id: loopCounter привязан bindMiInstanceVariables прямо на него.
     */
    private int miSubSlotOf(UUID pi, UUID innerTaskActivityId) {
        com.zorrodev.bpm.engine.dto.Activity live = dbService.getActivity(innerTaskActivityId);
        com.zorrodev.bpm.engine.dto.Token token = dbService.findToken(live.getToken()).orElseThrow();
        UUID containerInstanceId = token.getScopeActivityId();
        if (containerInstanceId == null) {
            // Строка САМОГО контейнера (спaун идёт на входящем токене без scope):
            // loopCounter привязан bindMiInstanceVariables прямо на неё.
            containerInstanceId = innerTaskActivityId;
        }
        // Тот же merge-read, что движок (getVariables(pi, scopeId): root+scope,
        // scoped wins) — вместо сырого JPA-ридера, слепого к JDBC-апсертам той же
        // транзакции (WO-REL-31 CR-1: upsert идёт мимо persistence-контекста).
        final UUID scopeId = containerInstanceId;
        return dbService.getVariables(pi, scopeId).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> Integer.parseInt(v.getValue().trim()))
            .orElseThrow(() -> new AssertionError("No scoped loopCounter on " + scopeId));
    }

    private List<String> assigneesOf(UUID pi, String bpmnElementId) {
        return activities(pi, bpmnElementId, ActivityStatus.CREATED).stream()
            .map(a -> userTaskAssignee(a.getId()))
            .toList();
    }

    @Autowired
    private com.zorrodev.bpm.engine.repository.UserTaskRepository userTaskRepository;

    @Autowired
    private com.zorrodev.bpm.engine.repository.ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private com.zorrodev.bpm.engine.service.DBService dbService;

    @Autowired
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

    private String userTaskAssignee(UUID activityId) {
        // Строку user_tasks читаем напрямую репозиторием (без refresh/entityManager:
        // refresh на уже завершённой в той же транзакции строке даёт stale/null).
        return userTaskRepository.findById(activityId).map(u -> u.getAssignee()).orElse(null);
    }

    /**
     * Критерий 1: минимальный процесс из отчёта — 3 параллельные задачи
     * assignee a/b/c, после сдачи всех results=[a,b,c], инцидентов 0.
     */
    @Transactional
    @Test
    void parallelMinimal_threeTasksAndPositionalResults() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-minimal.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> tasks = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(tasks).as("3 parallel MI-subprocess instances").hasSize(3);
        assertThat(tasks.stream().map(a -> userTaskAssignee(a.getId())).toList())
            .containsExactlyInAnyOrder("a", "b", "c");

        // Сдача в обратном порядке слотов — сборка всё равно позиционная.
        // Слот читаем с контейнера-инстанса (loopCounter привязан там, не на задаче).
        Map<UUID, Integer> slotByActivity = tasks.stream()
            .collect(Collectors.toMap(ActivityEntity::getId, a -> miSubSlotOf(pi, a.getId())));
        assertThat(slotByActivity.values()).containsExactlyInAnyOrder(1, 2, 3);
        tasks.stream()
            .sorted(Comparator.<ActivityEntity, Integer>comparing(t -> slotByActivity.get(t.getId())).reversed())
            .forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).as("no incidents").isZero();

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).containsExactly("a", "b", "c");
    }

    /**
     * Критерий 2: пустая коллекция — 0 инстансов, results=[], завершение.
     */
    @Transactional
    @Test
    void emptyCollection_noInstancesEmptyArrayCompletes() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-empty.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED)).isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(results.getType()).isEqualTo(ProcessVariableType.JSON);
        assertThat(new ObjectMapper().readValue(results.getTextValue(), List.class)).isEmpty();
    }

    /**
     * Критерий 4 (битое выражение): не коллекция — инцидент на элементе
     * (паттерн WO-C8-30), не падение и не тихий одиночный проход.
     */
    @Transactional
    @Test
    void brokenCollection_raisesIncidentOnTheSubProcess() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-broken-collection.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED))
            .as("no instances spawn on a broken collection").isEmpty();
        List<IncidentEntity> all = incidentRepository.findAll().stream()
            .filter(i -> activities(pi, "subProbe", ActivityStatus.ERROR).stream()
                .anyMatch(a -> a.getId().equals(i.getActivityId())))
            .toList();
        assertThat(all).as("incident parked on the MI subprocess").hasSize(1);
        assertThat(all.get(0).getMessage()).contains("subProbe");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    /**
     * Критерий 5: sequential — следующий инстанс стартует по завершении
     * предыдущего, outputCollection в порядке.
     */
    @Transactional
    @Test
    void sequential_nextStartsAfterPreviousCompletes() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-sequential.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> first = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(first).as("sequential spawns exactly one instance").hasSize(1);
        assertThat(userTaskAssignee(first.get(0).getId())).isEqualTo("a");

        runtimeService.completeUserTask(first.get(0).getId(), List.of());
        List<ActivityEntity> second = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(second).as("second instance starts after the first completes").hasSize(1);
        assertThat(userTaskAssignee(second.get(0).getId())).isEqualTo("b");

        runtimeService.completeUserTask(second.get(0).getId(), List.of());
        List<ActivityEntity> third = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(third).hasSize(1);
        assertThat(userTaskAssignee(third.get(0).getId())).isEqualTo("c");

        runtimeService.completeUserTask(third.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).containsExactly("a", "b", "c");
    }

    private static ProcessVariable jsonVar(String name, String json) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(json);
        return v;
    }

    /**
     * D1: lane-local переменная branchTag пишется выходом ioMapping (=item) и читается
     * следующим элементом ТОЙ ЖЕ ветки (utProbe: assignee =branchTag). Перекрёст
     * гарантируется КОНСТРУКЦИЕЙ: завершаем ОБА svcCreate (A затем B — в root-версии
     * B перезаписывает branchTag), и только потом завершаем utWaitA → utProbeA
     * создаётся и резолвит assignee. Без скоупа в инстанс A прочитал бы "B";
     * со скоупом — своё "A". Детерминировано, без везения.
     */
    @Transactional
    @Test
    void laneLocalOutputMapping_staysInsideItsOwnInstance() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-lane-local.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> creates = activities(pi, "svcCreate", ActivityStatus.CREATED);
        assertThat(creates).as("one create job per MI-subprocess instance").hasSize(2);
        // A = инстанс со слотом 1 (item "A"), B — со слотом 2 (item "B").
        // Различаем по item из видимых переменных строки (chain-merge, как видит
        // сам движок): fork внутри инстанса ведёт задачи на дочерних токенах,
        // непосредственный scope токена там не контейнер — идём полной цепочкой.
        java.util.function.Function<UUID, String> itemOf = activityId ->
            elementSupport.visibleVariables(pi, activityId).stream()
                .filter(v -> "item".equals(v.getName()))
                .findFirst()
                .map(v -> v.getValue().replace("\"", "").trim())
                .orElseThrow(() -> new AssertionError("No visible item on " + activityId));
        ActivityEntity createA = creates.stream()
            .filter(c -> "A".equals(itemOf.apply(c.getId()))).findFirst().orElseThrow();
        ActivityEntity createB = creates.stream()
            .filter(c -> "B".equals(itemOf.apply(c.getId()))).findFirst().orElseThrow();
        // A пишет branchTag="A", затем B пишет branchTag="B" (в root — перезапись).
        runtimeService.completeServiceTask(createA.getId(), List.of());
        runtimeService.completeServiceTask(createB.getId(), List.of());

        // Обе ветки припаркованы на utWait; utProbe ещё не созданы (чтение впереди).
        List<ActivityEntity> waits = activities(pi, "utWait", ActivityStatus.CREATED);
        assertThat(waits).as("both branches parked").hasSize(2);
        ActivityEntity waitA = waits.stream()
            .filter(p -> "A".equals(itemOf.apply(p.getId()))).findFirst().orElseThrow();
        ActivityEntity waitB = waits.stream()
            .filter(p -> "B".equals(itemOf.apply(p.getId()))).findFirst().orElseThrow();

        // A читает branchTag ПОСЛЕ записи B: без скоупа — "B" (перекрёст), со скоупом — "A".
        runtimeService.completeUserTask(waitA.getId(), List.of());
        List<ActivityEntity> probes = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(probes).as("A's probe created after B's write").hasSize(1);
        assertThat(userTaskAssignee(probes.get(0).getId()))
            .as("instance A reads its own branchTag, not B's overwrite").isEqualTo("A");

        runtimeService.completeUserTask(probes.get(0).getId(), List.of());
        runtimeService.completeUserTask(waitB.getId(), List.of());
        List<ActivityEntity> probesB = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(probesB).hasSize(1);
        assertThat(userTaskAssignee(probesB.get(0).getId()))
            .as("instance B reads its own branchTag").isEqualTo("B");
        runtimeService.completeUserTask(probesB.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Критерий 8 (вложенность): callActivity внутри MI-подпроцесса — каждый
     * инстанс зовёт дочерний процесс, дочерняя задача резолвит assignee из
     * inputMapping scope инстанса; по завершении дочерних задач outputCollection
     * в порядке, процесс завершён.
     */
    @Transactional
    @Test
    void callActivityInsideMiSub_eachInstanceCallsChildWithItsScope() throws Exception {
        processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/test-c840-call-child.bpmn")));
        UUID pdId = deploy("test-c840-mi-sub-call-inner.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> calls = activities(pi, "callWork", ActivityStatus.CREATED);
        assertThat(calls).as("one call activity per MI-subprocess instance").hasSize(2);

        List<com.zorrodev.bpm.engine.entity.ProcessInstanceEntity> children =
            processInstanceRepository.findAll(
                com.zorrodev.bpm.engine.repository.ProcessInstanceRepository.byParentProcessInstanceId(pi));
        assertThat(children).as("one child instance per MI-subprocess instance").hasSize(2);

        List<String> childAssignees = children.stream()
            .flatMap(c -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(c.getId()))
                .filter(a -> "childWork".equals(a.getBpmnElementId())
                    && a.getStatus() == ActivityStatus.CREATED)
                .map(a -> userTaskAssignee(a.getId())))
            .toList();
        assertThat(childAssignees)
            .as("each child task carries its own instance scope (item a/b), no cross-talk")
            .containsExactlyInAnyOrder("a", "b");

        children.stream()
            .flatMap(c -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(c.getId()))
                .filter(a -> "childWork".equals(a.getBpmnElementId())
                    && a.getStatus() == ActivityStatus.CREATED))
            .forEach(a -> runtimeService.completeUserTask(a.getId(), List.of()));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).as("no incidents").isZero();
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).containsExactly("a", "b");
    }

    /**
     * Критерий 8 (вложенность): обычный вложенный subProcess видит
     * цикл-переменную внешнего инстанса через цепочку скоупов (прямой путь
     * обоих инстансов); внутренний MI за шлюзом по root-флагу routeToMi
     * (условия шлюзов — root-контекст, pre-existing) проверяет MI-вход
     * внутри внешнего scope.
     */
    @Transactional
    @Test
    void nested_plainSubSeesOuterLoopVar_innerMiTaskSeesBoth() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-nested.bpmn");
        ProcessVariable routeToMi = new ProcessVariable();
        routeToMi.setName("routeToMi");
        routeToMi.setType(ProcessVariableType.BOOLEAN);
        routeToMi.setValue("true");
        UUID pi = start(pdId, List.of(jsonVar("items",
            "[{\"who\":\"a\",\"members\":[\"a1\",\"a2\"]},{\"who\":\"b\",\"members\":[\"b1\"]}]"), routeToMi));

        // Обычный вложенный подпроцесс: assignee =activity.who — цепочка скоупов.
        List<ActivityEntity> inner = activities(pi, "utInner", ActivityStatus.CREATED);
        assertThat(inner).as("one inner task per outer instance").hasSize(2);
        assertThat(inner.stream().map(a -> userTaskAssignee(a.getId())).toList())
            .containsExactlyInAnyOrder("a", "b");
        inner.forEach(a -> runtimeService.completeUserTask(a.getId(), List.of()));

        // Внутренний MI за шлюзом (routeToMi=true оба инстанса идут в MI-ветку).
        // inputCollection — из внешнего scope (=activity.members): инстанс "a"
        // даёт [a1,a2], инстанс "b" — [b1]. Без chain-контекста входа коллекция
        // не резолвится (в root её нет) — было бы 0 инстансов вместо 2+1.
        List<ActivityEntity> miTasks = activities(pi, "miTaskA", ActivityStatus.CREATED);
        assertThat(miTasks).as("a1+a2 from outer instance a, b1 from outer instance b").hasSize(3);
        assertThat(miTasks.stream().map(a -> userTaskAssignee(a.getId())).toList())
            .containsExactlyInAnyOrder("a1", "a2", "b1");
        miTasks.forEach(a -> runtimeService.completeUserTask(a.getId(), List.of()));

        // Оба внешних инстанса прошли внутренний MI и собрались позиционно.
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();

        // Внешний outputCollection — позиционно по внешним слотам.
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).containsExactly("a", "b");
    }

    /**
     * Критерий 8 (отмена): операторская отмена процесса с живым MI-подпроцессом —
     * те же четыре вызова DBService, что делает операторский путь
     * {@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance} (authz/canceling-фаза
     * там не меняются этим WO): все инстансы и их внутренние задачи сняты,
     * таймеры/подписки удалены, инстанс помечен отменённым, живых активностей 0.
     */
    @Transactional
    @Test
    void operatorCancel_cancelsAllMiInstancesJobsAndSubscriptions() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-minimal.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED))
            .as("3 live inner tasks before cancel").hasSize(3);
        assertThat(activities(pi, "subProbe", ActivityStatus.CREATED))
            .as("3 live MI container instances before cancel").hasSize(3);

        // Тот же порядок, что операторский путь (без authz/canceling-фазы — они не меняются).
        dbService.cancelActiveActivities(pi);
        dbService.deleteTimerJobsByProcessInstanceId(pi);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
        dbService.cancelProcessInstance(pi);

        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList()).as("no live activities left (instances + inner tasks)").isEmpty();
        assertThat(activities(pi, "subProbe", ActivityStatus.CANCELLED))
            .as("all 3 MI container instances cancelled").hasSize(3);
        assertThat(activities(pi, "utProbe", ActivityStatus.CANCELLED))
            .as("all 3 inner tasks cancelled").hasSize(3);
        // cancelled читаем с entity: toDTO маппер flag cancelled не копирует
        // (pre-existing DTO-gap, V7 — операторский путь возвращает IdDTO и на него
        // не влияет; здесь важен факт строки, а не DTO).
        com.zorrodev.bpm.engine.entity.ProcessInstanceEntity piEntity =
            processInstanceRepository.findById(pi).orElseThrow();
        assertThat(piEntity.isCancelled()).as("instance marked cancelled").isTrue();
        assertThat(piEntity.getCompletedAt()).as("cancel completes the instance").isNotNull();
    }

    /**
     * Критерий 8 (отмена): interrupting message boundary на MI-подпроцессе —
     * корреляция гасит ВСЕ инстансы и их внутренности (cancelMiSubProcess через
     * fireBoundary message-пути), поток идёт с границы. Без чистки инстансов
     * выжившие задачи остались бы CREATED без scope (тихая утечка токенов).
     */
    @Transactional
    @Test
    void messageBoundaryOnMiSub_cancelsAllInstancesAndContinues() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-message-boundary.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED))
            .as("one parked task per MI-subprocess instance").hasSize(2);
        assertThat(activities(pi, "subProbe", ActivityStatus.CREATED))
            .as("both MI container instances live").hasSize(2);

        activityService.correlateMessage("cancel-order", pi, List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED))
            .as("no live inner tasks left hanging").isEmpty();
        assertThat(activities(pi, "subProbe", ActivityStatus.CANCELLED))
            .as("both MI container instances cancelled").hasSize(2);
        assertThat(activities(pi, "endAfter", ActivityStatus.COMPLETED))
            .as("boundary continuation ran").hasSize(1);
        assertThat(activities(pi, "endRepro", ActivityStatus.COMPLETED))
            .as("normal exit not taken").isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Критерий 8 (отмена): interrupting error boundary на MI-подпроцессе —
     * ошибка внутри одного инстанса гасит ВСЕ инстансы, поток идёт с границы.
     * Ошибка стреляет из service task внутри первого инстанса
     * (throwServiceTaskError — тот же путь, что c835-errbnd-тест).
     */
    @Transactional
    @Test
    void errorBoundaryOnMiSub_cancelsAllInstancesAndContinues() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-error-boundary.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> tasks = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(tasks).hasSize(2);
        tasks.forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        List<ActivityEntity> throwers = activities(pi, "svcThrow", ActivityStatus.CREATED);
        assertThat(throwers).as("one thrower per MI instance").hasSize(2);

        // Ошибка из первого инстанса — граница на контейнере гасит оба инстанса.
        activityService.throwServiceTaskError(throwers.get(0).getId(), "E-1", List.of());

        assertThat(activities(pi, "utProbe", ActivityStatus.CANCELLED)).isEmpty();
        assertThat(activities(pi, "svcThrow", ActivityStatus.CREATED))
            .as("sibling thrower cancelled with the scope").isEmpty();
        assertThat(activities(pi, "subProbe", ActivityStatus.CANCELLED))
            .as("both MI container instances cancelled").hasSize(2);
        assertThat(activities(pi, "endAfter", ActivityStatus.COMPLETED))
            .as("boundary continuation ran").hasSize(1);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
    }

    /**
     * Критерий 8 (отмена): terminate end внутри одного инстанса —
     * соседний инстанс снят (cancelActivity; его задача гаснет как часть
     * scope-отмены — CANCELLED или остаётся CREATED-но-мёртвой, если scope-цепочка
     * её не покрыла: главный инвариант — процесс завершён без висящих токенов),
     * поток продолжен с выхода контейнера.
     * Строгая проверка задач — в errorBoundary-тесте выше (там scope-отмена
     * идёт через cancelMiOrSingleScope и покрывает внутренности).
     */
    @Transactional
    @Test
    void terminateInsideInstance_cancelsSiblingsAndContinues() throws Exception {
        UUID pdId = deploy("test-c840-mi-sub-terminate.bpmn");
        UUID pi = start(pdId, List.of());

        List<ActivityEntity> tasks = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(tasks).hasSize(2);

        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());

        assertThat(activities(pi, "subProbe", ActivityStatus.CANCELLED)).hasSize(2);
        assertThat(activities(pi, "endAfter", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        assertThat(activities(pi, "utProbe", ActivityStatus.CREATED))
            .as("no live sibling tasks left hanging").isEmpty();
    }
}
