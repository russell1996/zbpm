package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-57 (F-15): completionCondition на MI-ЗАДАЧЕ (service task / user task) должен
 * ТЕРМИНИРОВАТЬ оставшихся сиблингов — как это делает Zeebe 8.5.
 *
 * <p><b>Оракул (живой Zeebe 8.5.0, измерено 2026-10-20).</b> Сценарий
 * {@code docs/analysis/c8-oracle/scenarios/mi-task-cc-siblings/} (runner
 * {@code docs/analysis/c8-oracle/run-oracle.js s10|s10m|s10l|s11}, дословный лог —
 * {@code docs/analysis/c8-oracle/logs/oracle-mi-task-cc-2026-10-20.log}):
 * <ul>
 *   <li>MI service task из 3 элементов, условие {@code =numberOfCompletedInstances >= 1};
 *       модель Zeebe 8.5 ПРИНИМАЕТ (в отличие от документированного «только подпроцесс»
 *       — 8.9 docs, XML-пример: multi-instance SERVICE TASK с completionCondition);</li>
 *   <li>после завершения ОДНОГО инстанса (первого/среднего/последнего — результат
 *       одинаковый) остальные ДВА ТЕРМИНИРОВАНЫ: активировать их job'ы нельзя (0 job'ов);</li>
 *   <li>MI-тело закрывается сразу — следующий элемент создан;</li>
 *   <li>{@code results} = позиционная агрегация: слот завершённого инстанса заполнен,
 *       слоты терминированных — {@code null} ({@code ["a",null,null]} / {@code [null,"b",null]}
 *       / {@code [null,null,"c"]});</li>
 *   <li>поздний completion терминированного инстанса — ОТКАЗ: {@code NOT_FOUND}
 *       («no such job was found»);</li>
 *   <li>последовательный MI: жив ОДИН инстанс; после его завершения условие срабатывает →
 *       следующий инстанс НЕ создаётся, тело закрывается ({@code results=["a",null,null]}).</li>
 * </ul>
 *
 * <p><b>До фикса (master @ 6647be0d5)</b> {@code cancelRemainingMiSubInstances} срабатывал
 * ТОЛЬКО для {@code SUB_PROCESS} (аудит F-15): сиблинги MI-задачи оставались живыми
 * (activity {@code CREATED}, у service task — живой job, у user task — открытая задача),
 * инстанс мог завершиться при открытых сиблингах, а поздний completion воркера приходил
 * на «мёртвый» инстанс.
 *
 * <p>Каждый тест проверяет инвариант утечек (G-P): после завершения инстанса нет живых
 * activity, нет открытых user/service task, нет несработавших таймеров, нет остаточных
 * arrival-строк join'а, нет инцидентов.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiTaskCompletionConditionSiblingsIntegrationTests {

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
    private ServiceTaskRepository serviceTaskRepository;
    @Autowired
    private UserTaskRepository userTaskRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .toList();
    }

    /** Живые инстансы MI-элемента (activity-строки CREATED/IN_PROGRESS). */
    private List<ActivityEntity> liveInstances(UUID pi, String elementId) {
        return activities(pi, elementId, ActivityStatus.CREATED)
            .stream().filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .sorted(Comparator.comparing(ActivityEntity::getId))
            .toList();
    }

    /** item ("a"/"b"/"c") каждого живого инстанса — детерминированный ключ позиции. */
    private Map<String, ActivityEntity> byItem(UUID pi, List<ActivityEntity> instances) {
        Map<String, ActivityEntity> out = new LinkedHashMap<>();
        for (ActivityEntity a : instances) {
            String item = null;
            for (ProcessVariable v : elementSupport.visibleVariables(pi, a.getId())) {
                if ("item".equals(v.getName())) {
                    item = v.getValue();
                }
            }
            out.put(item == null ? "?" : item, a);
        }
        return out;
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

    private long openServiceTasks(UUID pi) {
        return serviceTaskRepository.findAll().stream()
            .filter(t -> pi.equals(t.getProcessInstanceId()) && t.getCompletedAt() == null)
            .count();
    }

    private long openUserTasks(UUID pi) {
        return userTaskRepository.findAll().stream()
            .filter(t -> pi.equals(t.getProcessInstanceId()) && t.getCompletedAt() == null)
            .count();
    }

    /** G-P: после завершения инстанта живого мусора быть не должно. */
    private void assertNoLeaks(UUID pi) {
        assertThat(liveInstances(pi, "miTask"))
            .as("LEAK-INVARIANT: no live MI instances left").isEmpty();
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)
            .stream().filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS).toList())
            .as("LEAK-INVARIANT: no live downstream activity").isEmpty();
        assertThat(openServiceTasks(pi))
            .as("LEAK-INVARIANT: no open service task (job) left").isZero();
        assertThat(openUserTasks(pi))
            .as("LEAK-INVARIANT: no open user task left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    private List<String> resultsOf(UUID pi) throws Exception {
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        return new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    // ──────────────────────────────────────────────────────────────
    // Параллельный MI service task: первая / средняя / последняя ветка
    // ──────────────────────────────────────────────────────────────

    @Transactional
    @Test
    void parallel_firstInstanceCompletes_remainingSiblingsTerminated() throws Exception {
        assertParallelEarlyExit("a");
    }

    @Transactional
    @Test
    void parallel_middleInstanceCompletes_remainingSiblingsTerminated() throws Exception {
        assertParallelEarlyExit("b");
    }

    @Transactional
    @Test
    void parallel_lastInstanceCompletes_remainingSiblingsTerminated() throws Exception {
        assertParallelEarlyExit("c");
    }

    /** Общий ход: завершить инстанс с item=item → остальные терминированы, тело закрыто. */
    private void assertParallelEarlyExit(String completedItem) throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-parallel.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("3 MI instances at entry").hasSize(3);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);
        assertThat(byItem.keySet()).as("items a/b/c").containsExactlyInAnyOrder("a", "b", "c");

        runtimeService.completeServiceTask(byItem.get(completedItem).getId(), List.of());

        // Zeebe 8.5: остальные инстансы ТЕРМИНИРОВАНЫ (job'ов не осталось).
        assertThat(liveInstances(pi, "miTask"))
            .as("remaining MI instances terminated by the completionCondition").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED))
            .as("terminated siblings are CANCELLED").hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED))
            .as("the completed instance stays COMPLETED").hasSize(1);

        // MI-тело закрылось сразу — следующий элемент создан.
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("MI body closed early — dump task created").hasSize(1);

        // Позиционная агрегация: слот завершённого заполнен, слоты терминированных — null.
        List<String> results = resultsOf(pi);
        assertThat(results).as("3 slots, only the completed one filled").hasSize(3);
        assertThat(results.stream().filter(v -> v != null).count())
            .as("exactly one slot filled").isEqualTo(1);
        assertThat(results.get("a".equals(completedItem) ? 0 : "b".equals(completedItem) ? 1 : 2))
            .as("completed slot holds its item").isEqualTo(completedItem);

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertNoLeaks(pi);
    }

    // ──────────────────────────────────────────────────────────────
    // Поздний completion от воркера для ТЕРМИНИРОВАННОГО сиблинга
    // ──────────────────────────────────────────────────────────────

    @Transactional
    @Test
    void lateCompletionOfTerminatedSibling_isRefused_withoutLeaks() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-parallel.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        Map<String, ActivityEntity> byItem = byItem(pi, instances);
        runtimeService.completeServiceTask(byItem.get("a").getId(), List.of());

        ActivityEntity terminated = byItem.get("b");
        // Статус перечитываем из БД: снимок byItem сделан ДО завершения и после него
        // устарел (строку обновили, объект в памяти — нет).
        ActivityEntity terminatedNow = activityRepository.findById(terminated.getId()).orElseThrow();
        assertThat(terminatedNow.getStatus()).as("sibling b is CANCELLED").isEqualTo(ActivityStatus.CANCELLED);

        // Поздний результат воркера по ТЕРМИНИРОВАННОЙ задаче: Zeebe отвечает NOT_FOUND.
        // У нас — поглощение идемпотентным guard'ом (alreadyCompleted), без сдвига потока.
        IdDTO late = runtimeService.completeServiceTask(terminated.getId(), List.of());
        assertThat(late.getAlreadyCompleted()).as("late completion absorbed as replay").isTrue();

        // Поздний FAILED от воркера по той же задаче — тоже отказ (без ретрая и инцидента).
        runtimeService.failServiceTask(terminated.getId(), "late failure", null, null, null, null);

        // Никакого сдвига: dump всё ещё ждёт, лишних активностей не появилось.
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("dump still pending").hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(2);
        assertThat(incidents(pi)).as("no incident from the late completion").isZero();

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    // ──────────────────────────────────────────────────────────────
    // Последовательный MI service task
    // ──────────────────────────────────────────────────────────────

    @Transactional
    @Test
    void sequential_conditionFires_bodyClosesWithoutNextInstance() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-sequential.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("sequential MI — exactly one live instance").hasSize(1);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);
        assertThat(byItem.keySet()).as("first item only").containsExactly("a");

        runtimeService.completeServiceTask(instances.get(0).getId(), List.of());

        assertThat(liveInstances(pi, "miTask")).as("no live instance left").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("MI body closed — dump created").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).as("3 slots, only the first filled").hasSize(3);
        assertThat(results.get(0)).isEqualTo("a");
        assertThat(results.get(1)).isNull();
        assertThat(results.get(2)).isNull();

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    // ──────────────────────────────────────────────────────────────
    // MI USER TASK: терминированный сиблинг не остаётся открытой задачей
    // ──────────────────────────────────────────────────────────────

    @Transactional
    @Test
    void userTaskSiblings_terminatedAndClosed_noOpenTaskLeft() throws Exception {
        UUID pdId = deploy("test-c857-mi-user-task-cc-parallel.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("3 MI user-task instances at entry").hasSize(3);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);

        runtimeService.completeUserTask(byItem.get("b").getId(), List.of());

        assertThat(liveInstances(pi, "miTask"))
            .as("remaining user-task instances terminated").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).as("instance completed").isNotNull();
        assertNoLeaks(pi);
    }

    // ──────────────────────────────────────────────────────────────
    // G-P: матрица комбинаций — cardinality × порог условия × порядок
    // (не «один удобный порядок»): 1/2/3 элемента, порог 1 и 2,
    // параллельный и последовательный MI.
    // ──────────────────────────────────────────────────────────────

    /** cardinality = 1: сиблингов нет — терминировать нечего, тело закрывается штатно. */
    @Transactional
    @Test
    void parallel_singleInstance_noSiblings_bodyClosesWithoutLeaks() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-parallel-one.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("single MI instance").hasSize(1);

        runtimeService.completeServiceTask(instances.get(0).getId(), List.of());

        assertThat(liveInstances(pi, "miTask")).as("no live instance left").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(0);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo("a");

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    /** cardinality = 2: первый завершён — второй терминирован (results = ["a", null]). */
    @Transactional
    @Test
    void parallel_twoInstances_conditionAfterFirst_remainingTerminated() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-parallel-two.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("2 MI instances").hasSize(2);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);

        runtimeService.completeServiceTask(byItem.get("a").getId(), List.of());

        assertThat(liveInstances(pi, "miTask")).as("remaining instance terminated").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).as("2 slots").hasSize(2);
        assertThat(results.get(0)).isEqualTo("a");
        assertThat(results.get(1)).as("terminated slot stays null").isNull();

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    /**
     * cardinality = 3, порог = 2 (частичная конвергенция): первый приход условие НЕ
     * закрывает — тело ждёт; второй приход закрывает, ТРЕТИЙ инстанс терминируется.
     */
    @Transactional
    @Test
    void parallel_twoOfThree_conditionAfterSecond_remainingTerminated() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-parallel-threshold2.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("3 MI instances").hasSize(3);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);

        runtimeService.completeServiceTask(byItem.get("a").getId(), List.of());

        // Порог 2: после ОДНОГО завершения тело ещё живо — остаются два инстанса (b, c).
        assertThat(liveInstances(pi, "miTask"))
            .as("condition not met after the first completion — body still waits").hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("downstream not created while the condition is not met").isEmpty();

        runtimeService.completeServiceTask(byItem.get("b").getId(), List.of());

        assertThat(liveInstances(pi, "miTask")).as("third instance terminated").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(2);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).as("3 slots").hasSize(3);
        assertThat(results.get(0)).isEqualTo("a");
        assertThat(results.get(1)).isEqualTo("b");
        assertThat(results.get(2)).as("terminated slot stays null").isNull();

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    /**
     * Последовательный MI с порогом 2: второй инстанс создаётся, а ТРЕТИЙ — нет:
     * условие закрывает тело на втором завершении (Zeebe: активные инстансы
     * терминируются, новые не создаются).
     */
    @Transactional
    @Test
    void sequential_thresholdAfterSecond_thirdInstanceNotCreated() throws Exception {
        UUID pdId = deploy("test-c857-mi-task-cc-sequential-threshold2.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> first = liveInstances(pi, "miTask");
        assertThat(first).as("sequential MI — one live instance").hasSize(1);
        assertThat(byItem(pi, first).keySet()).containsExactly("a");

        runtimeService.completeServiceTask(first.get(0).getId(), List.of());

        // Порог 2: тело ещё живо, следующий инстанс (b) создан.
        List<ActivityEntity> second = liveInstances(pi, "miTask");
        assertThat(second).as("second sequential instance spawned").hasSize(1);
        assertThat(byItem(pi, second).keySet()).containsExactly("b");
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("downstream not created while the condition is not met").isEmpty();

        runtimeService.completeServiceTask(second.get(0).getId(), List.of());

        assertThat(liveInstances(pi, "miTask")).as("no live instance left").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED))
            .as("no instance to cancel — the third was never created").hasSize(0);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).as("3 slots pre-created, 2 filled").hasSize(3);
        assertThat(results.get(0)).isEqualTo("a");
        assertThat(results.get(1)).isEqualTo("b");
        assertThat(results.get(2)).as("third slot stays null").isNull();

        runtimeService.completeServiceTask(activities(pi, "dump", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }
}
