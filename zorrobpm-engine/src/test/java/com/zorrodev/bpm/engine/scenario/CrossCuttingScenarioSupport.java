package com.zorrodev.bpm.engine.scenario;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * WO-OPS-28, ФАЗА 5 («сквозные»): драйверы осей среза F5.
 *
 * <p>Три оси и что каждая проверяет:
 * <ul>
 *   <li><b>рестарт движка посреди процесса</b> — процесс стартует, на середине
 *       (пока живая задача ждёт воркера) движок «перезапускается»: продолжать
 *       процесс должен ТОЛЬКО СВЕЖИЙ экземпляр движка (второй Spring-контекст на
 *       той же БД). Если бы состояние процесса жило в памяти первого движка,
 *       продолжить было бы нечем — это и есть проверка «состояние движка
 *       долговечно»;</li>
 *   <li><b>параллельные завершения</b> — ЧЕТЫРЕ ветви форка завершаются ОДНОВРЕМЕННО
 *       (четыре реальных потока, общий барьер — не «последовательная имитация»),
 *       и проверяется, что джойн сработал РОВНО ОДИН раз, ветви не потеряны и
 *       инстанс завершился;</li>
 *   <li><b>миграция версии определения</b> — инстанс стартует на v1, затем
 *       деплоится v2 ТОГО ЖЕ ключа; проверяется, какой версией продолжится
 *       живой инстанс (в Zeebe 8.5 — своей: версия фиксируется на старте).</li>
 * </ul>
 *
 * <p>Наблюдение — то же, что в срезах F1/F2: «наблюдательная» задача
 * {@code dump} в конце схемы; её payload = переменные корневого скоупа. Плюс
 * axis-специфичные факты в {@code extra}.
 *
 * <p>Драйвер НЕ чинит и не падает на расхождении: он фиксирует наблюдаемый
 * факт (по кампании баги чинятся отдельными задачами). Проверка факта —
 * прибитая наша часть в {@code scenario-matrix/cross-expectations.json}.
 */
public final class CrossCuttingScenarioSupport {

    /** Наблюдение по одному прогону оси. */
    public record Observation(String scenarioId, String axis, String status,
            Map<String, String> vars, List<String> leaks, List<String> incidents,
            String error, Map<String, Object> extra) {

        /** Наблюдение в виде карты — для прибитого факта (JSON). */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scenarioId", scenarioId);
            map.put("axis", axis);
            map.put("status", status);
            map.put("vars", vars);
            map.put("leaks", leaks);
            map.put("incidents", incidents);
            map.put("error", error);
            map.put("extra", extra);
            return map;
        }
    }

    /**
     * «Перезапущенный» движок: свежий экземпляр, поднятый ПОСЛЕ старта процесса.
     *
     * <p>Контракт минимален: всё, что нужно для продолжения процесса, —
     * завершение задачи и чтение живых activity. Движок поднимает тест (у H2 —
     * на том же {@code jdbc:h2:mem:test}, у PG — на том же PostgreSQL).
     */
    public interface RestartedEngine extends AutoCloseable {
        RuntimeService runtimeService();

        ActivityRepository activityRepository();

        @Override
        void close();
    }

    /** Фабрика «перезапущенного» движка (у H2 и у PG — разная: свой datasource). */
    public interface RestartedEngineFactory {
        RestartedEngine open() throws Exception;
    }

    private final ProcessDefinitionService processDefinitionService;
    private final RuntimeService runtimeService;
    private final QueryService queryService;
    private final ActivityRepository activityRepository;
    private final VariableRepository variableRepository;
    private final IncidentRepository incidentRepository;
    private final JdbcTemplate jdbc;

    public CrossCuttingScenarioSupport(ProcessDefinitionService processDefinitionService,
            RuntimeService runtimeService, QueryService queryService,
            ActivityRepository activityRepository, VariableRepository variableRepository,
            IncidentRepository incidentRepository, JdbcTemplate jdbc) {
        this.processDefinitionService = processDefinitionService;
        this.runtimeService = runtimeService;
        this.queryService = queryService;
        this.activityRepository = activityRepository;
        this.variableRepository = variableRepository;
        this.incidentRepository = incidentRepository;
        this.jdbc = jdbc;
    }

    // ─── Ось 1: рестарт движка посреди процесса ──────────────────────────

    /**
     * Старт → живая задача на середине → <b>рестарт движка</b> → продолжение
     * СВЕЖИМ движком → наблюдение → конец.
     *
     * <p>Наблюдаемые факты: {@code restartedEngineSawTask} (свежий движок увидел
     * живую задачу — то есть состояние в БД, а не в памяти), {@code continuedAfterRestart}
     * (после рестарта процесс дошёл до следующей задачи) и терминальный статус.
     */
    public Observation restartMidProcess(BpmnScenario scenario, RestartedEngineFactory factory) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = null;
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            var definition = processDefinitionService.addProcessDefinition(scenario.bpmn());
            pi = start(scenario, definition.getId());

            List<ActivityEntity> mid = awaitLive(pi, List.of("stMid"));
            extra.put("midTaskCreatedBeforeRestart", !mid.isEmpty());
            if (mid.isEmpty()) {
                return observation(scenario, pi, baseline, "NO_MID_TASK", extra,
                        "the mid-process task never appeared", Map.of());
            }

            try (RestartedEngine restarted = factory.open()) {
                extra.put("engineInstances", 2);
                // Свежий движок ищет живую задачу САМ (в своей БД) и завершает её.
                List<ActivityEntity> seenByRestarted = awaitLiveBy(restarted.activityRepository(),
                        pi, List.of("stMid"));
                extra.put("restartedEngineSawTask", !seenByRestarted.isEmpty());
                if (seenByRestarted.isEmpty()) {
                    return observation(scenario, pi, baseline, "TASK_LOST_BY_RESTART", extra,
                            "the restarted engine did not see the live task", Map.of());
                }
                restarted.runtimeService().completeServiceTask(seenByRestarted.get(0).getId(), vars("{}"));

                List<ActivityEntity> after = awaitLive(pi, List.of("stAfter"));
                extra.put("continuedAfterRestart", !after.isEmpty());
                if (after.isEmpty()) {
                    return observation(scenario, pi, baseline, "STALLED_AFTER_RESTART", extra,
                            "the process did not continue after the restart", Map.of());
                }
                runtimeService.completeServiceTask(after.get(0).getId(), vars("{\"phase\":\"after\"}"));
            }

            Map<String, String> observed = observeAndFinish(pi, scenario);
            return observation(scenario, pi, baseline, statusOf(pi), extra, "", observed);
        } catch (Exception e) {
            return observation(scenario, pi, baseline, pi == null ? "FAILED" : statusOf(pi), extra,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        }
    }

    // ─── Ось 2: параллельные завершения (гонка за instance-lock) ──────────

    /**
     * Форк в ЧЕТЫРЕ ветви; все задачи завершаются ОДНОВРЕМЕННО из четырёх реальных
     * потоков (общий {@link CyclicBarrier} — старт одновременный по Команде).
     *
     * <p>Проверяется: джойн сработал РОВНО ОДИН раз (одна activity {@code dump}
     * на инстанс), ветви не потеряны (переменные всех ветвей на месте), инстанс
     * завершён.
     *
     * @param iterations сколько инстансов прогнать (каждый — своя четвёрка потоков)
     */
    public Observation parallelRace(BpmnScenario scenario, int iterations) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        Map<String, Object> extra = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int completed = 0;
        int dumpActivityCounts = 0;
        Map<String, String> lastVars = Map.of();
        try {
            var definition = processDefinitionService.addProcessDefinition(scenario.bpmn());
            List<String> branches = scenario.taskElementIds();
            for (int i = 0; i < iterations; i++) {
                UUID pi = start(scenario, definition.getId());
                List<ActivityEntity> live = awaitLiveAll(pi, branches);
                if (live.size() < branches.size()) {
                    errors.add("iteration " + i + ": forked tasks did not appear");
                    continue;
                }

                // N РЕАЛЬНЫХ потоков, старт по барьеру — не последовательная имитация.
                CyclicBarrier barrier = new CyclicBarrier(branches.size());
                ExecutorService pool = Executors.newFixedThreadPool(branches.size());
                try {
                    List<Future<Void>> futures = new ArrayList<>();
                    for (ActivityEntity activity : live) {
                        String elementId = activity.getBpmnElementId();
                        futures.add(pool.submit(() -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            runtimeService.completeServiceTask(activity.getId(),
                                    vars("{\"" + elementId + "\":\"" + elementId + "\"}"));
                            return null;
                        }));
                    }
                    for (Future<Void> future : futures) {
                        future.get(30, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    errors.add("iteration " + i + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    pool.shutdownNow();
                }

                List<ActivityEntity> dump = awaitLive(pi, List.of("dump"));
                dumpActivityCounts = countActivities(pi, "dump");
                if (dump.isEmpty()) {
                    errors.add("iteration " + i + ": the join never produced the observation task");
                    continue;
                }
                lastVars = observeAndFinish(pi, scenario);
                if ("COMPLETED".equals(statusOf(pi))) {
                    completed++;
                } else {
                    errors.add("iteration " + i + ": instance status " + statusOf(pi));
                }
            }
            extra.put("iterations", iterations);
            extra.put("instancesCompleted", completed);
            extra.put("dumpActivityRowsMax", dumpActivityCounts);
            extra.put("errors", errors);
            String status = completed == iterations && errors.isEmpty() ? "COMPLETED" : "DEGRADED";
            return observation(scenario, null, baseline, status, extra,
                    errors.isEmpty() ? "" : String.join("; ", errors), lastVars);
        } catch (RuntimeException e) {
            return observation(scenario, null, baseline, "FAILED", extra,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        }
    }

    // ─── Ось 3: миграция версии определения для живого инстанса ──────────

    /**
     * Старт на v1 → деплой v2 ТОГО ЖЕ ключа → завершение задачи → проверка,
     * КАКОЙ версией продолжился живой инстанс.
     *
     * <p>Наблюдаемые факты: {@code continuedWith} (какая задача появилась после
     * {@code stA}: {@code stV1} — своей версией, {@code stV2} — новой),
     * {@code deployedVersionDelta} (на сколько версий вырос ключ между деплоями —
     * АБСОЛЮТНЫЕ номера версий не прибиваются: они зависят от порядка прогонов),
     * {@code instanceVersionEqualsDeployedV1} (версия определения инстанса = та,
     * на которой он стартовал).
     */
    public Observation versionMigration(BpmnScenario v1, BpmnScenario v2) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = null;
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            var defV1 = processDefinitionService.addProcessDefinition(v1.bpmn());
            pi = start(v1, defV1.getId());
            var defV2 = processDefinitionService.addProcessDefinition(v2.bpmn());
            extra.put("deployedVersionDelta", defV2.getVersion() - defV1.getVersion());

            List<ActivityEntity> stA = awaitLive(pi, List.of("stA"));
            if (stA.isEmpty()) {
                return observation(v1, pi, baseline, "NO_TASK", extra, "stA never appeared", Map.of());
            }
            runtimeService.completeServiceTask(stA.get(0).getId(), vars("{\"phase\":\"a\"}"));

            String continuedWith = "";
            for (String candidate : List.of("stV1", "stV2")) {
                if (!awaitLive(pi, List.of(candidate)).isEmpty()) {
                    continuedWith = candidate;
                    break;
                }
            }
            extra.put("continuedWith", continuedWith);
            extra.put("instanceVersionEqualsDeployedV1", defV1.getVersion().equals(definitionVersionOf(pi)));

            if (continuedWith.isEmpty()) {
                return observation(v1, pi, baseline, "STALLED", extra,
                        "neither stV1 nor stV2 appeared", Map.of());
            }
            List<ActivityEntity> next = awaitLive(pi, List.of(continuedWith));
            runtimeService.completeServiceTask(next.get(0).getId(), vars("{\"phase\":\"" + continuedWith + "\"}"));

            Map<String, String> observed = observeAndFinish(pi, v1);
            return observation(v1, pi, baseline, statusOf(pi), extra, "", observed);
        } catch (RuntimeException e) {
            return observation(v1, pi, baseline, pi == null ? "FAILED" : statusOf(pi), extra,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        }
    }

    /**
     * КОНТРОЛЬ оси «миграция версии»: инстанс, стартовавший на НОВОЙ версии,
     * продолжается на ней же (вторая сторона того же правила).
     */
    public Observation versionControl(BpmnScenario scenario) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = null;
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            var definition = processDefinitionService.addProcessDefinition(scenario.bpmn());
            pi = start(scenario, definition.getId());
            extra.put("instanceVersionEqualsDeployed", definition.getVersion().equals(definitionVersionOf(pi)));

            List<ActivityEntity> stA = awaitLive(pi, List.of("stA"));
            if (stA.isEmpty()) {
                return observation(scenario, pi, baseline, "NO_TASK", extra, "stA never appeared", Map.of());
            }
            runtimeService.completeServiceTask(stA.get(0).getId(), vars("{\"phase\":\"a\"}"));

            String continuedWith = "";
            for (String candidate : List.of("stV1", "stV2")) {
                if (!awaitLive(pi, List.of(candidate)).isEmpty()) {
                    continuedWith = candidate;
                    break;
                }
            }
            extra.put("continuedWith", continuedWith);
            if (continuedWith.isEmpty()) {
                return observation(scenario, pi, baseline, "STALLED", extra,
                        "neither stV1 nor stV2 appeared", Map.of());
            }
            List<ActivityEntity> next = awaitLive(pi, List.of(continuedWith));
            runtimeService.completeServiceTask(next.get(0).getId(), vars("{\"phase\":\"" + continuedWith + "\"}"));

            Map<String, String> observed = observeAndFinish(pi, scenario);
            return observation(scenario, pi, baseline, statusOf(pi), extra, "", observed);
        } catch (RuntimeException e) {
            return observation(scenario, pi, baseline, pi == null ? "FAILED" : statusOf(pi), extra,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        }
    }

    // ─── Общие помощники ─────────────────────────────────────────────────

    /** Старт инстанса сценария (переменные — из varsJson сценария). */
    private UUID start(BpmnScenario scenario, UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        dto.setVariables(parseVariables(scenario.varsJson()));
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
    }

    /** Живая activity по id элемента (ждёт появления). */
    private List<ActivityEntity> awaitLive(UUID pi, List<String> elementIds) {
        return awaitLiveBy(activityRepository, pi, elementIds);
    }

    /**
     * Живые activity по ВСЕМ перечисленным id элемента (ждёт, пока появятся все).
     *
     * <p>Форк создаёт ветви ПОСЛЕДОВАТЕЛЬНО, поэтому «хотя бы одна живая» —
     * не то же самое, что «все ветви живы»: гонка иначе живёт в ТЕСТЕ, а не в
     * движке (и тест краснеет на своём же ожидании).
     */
    private List<ActivityEntity> awaitLiveAll(UUID pi, List<String> elementIds) {
        for (int i = 0; i < 100; i++) {
            List<ActivityEntity> live = activityRepository.findAll().stream()
                    .filter(a -> pi.equals(a.getProcessInstanceId()))
                    .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                    .filter(a -> elementIds.contains(a.getBpmnElementId()))
                    .toList();
            boolean allPresent = elementIds.stream()
                    .allMatch(id -> live.stream().anyMatch(a -> id.equals(a.getBpmnElementId())));
            if (allPresent && live.size() >= elementIds.size()) {
                return live;
            }
            sleep(50);
        }
        return List.of();
    }

    /** Живая activity по id элемента в УКАЗАННОМ репозитории (свежий движок). */
    private List<ActivityEntity> awaitLiveBy(ActivityRepository repository, UUID pi, List<String> elementIds) {
        for (int i = 0; i < 100; i++) {
            List<ActivityEntity> live = repository.findAll().stream()
                    .filter(a -> pi.equals(a.getProcessInstanceId()))
                    .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                    .filter(a -> elementIds.contains(a.getBpmnElementId()))
                    .toList();
            if (!live.isEmpty()) {
                return live;
            }
            sleep(50);
        }
        return List.of();
    }

    /** Сколько activity-строк данного элемента у инстанса (джойн должен дать РОВНО одну). */
    private int countActivities(UUID pi, String elementId) {
        return (int) activityRepository.findAll().stream()
                .filter(a -> pi.equals(a.getProcessInstanceId()))
                .filter(a -> elementId.equals(a.getBpmnElementId()))
                .count();
    }

    /** Наблюдение: дождаться {@code dump}, снять переменные корня, завершить dump. */
    private Map<String, String> observeAndFinish(UUID pi, BpmnScenario scenario) {
        List<ActivityEntity> dump = awaitLive(pi, List.of(scenario.dumpElementId()));
        if (dump.isEmpty()) {
            return Map.of();
        }
        Map<String, String> observed = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            if (v.getName() != null && v.getName().startsWith("_")) {
                continue;
            }
            observed.put(v.getName(), v.getTextValue());
        }
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());
        return observed;
    }

    /** Терминальный статус инстанса. */
    private String statusOf(UUID pi) {
        var instance = queryService.getProcessInstance(pi);
        if (instance == null) {
            return "NOT_FOUND";
        }
        return instance.getCompletedAt() != null ? "COMPLETED" : "NOT_COMPLETED";
    }

    /** Версия определения, на которой живёт инстанс. */
    private Integer definitionVersionOf(UUID pi) {
        var instance = queryService.getProcessInstance(pi);
        if (instance == null) {
            return null;
        }
        return instance.getProcessVersion();
    }

    /** Наблюдение + нарушения инварианта утечек по этому инстансу. */
    private Observation observation(BpmnScenario scenario, UUID pi, LeakInvariantChecker.Baseline baseline,
            String status, Map<String, Object> extra, String error, Map<String, String> vars) {
        List<String> leaks = pi == null ? List.of() : leaksOf(pi, baseline);
        List<String> incidents = pi == null ? List.of() : incidentsOf(pi);
        return new Observation(scenario.id(), scenario.content(), status, vars, leaks, incidents, error, extra);
    }

    /** Нарушения инварианта утечек (WO-OPS-26) по этому инстансу. */
    private List<String> leaksOf(UUID pi, LeakInvariantChecker.Baseline baseline) {
        return LeakInvariantChecker.check(jdbc, baseline).own().stream()
                .filter(v -> pi.equals(v.instanceId()))
                .map(Object::toString)
                .toList();
    }

    /** Сообщения инцидентов инстанса. */
    private List<String> incidentsOf(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
                .filter(a -> pi.equals(a.getProcessInstanceId()))
                .map(ActivityEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (IncidentEntity incident : incidentRepository.findAll()) {
            if (activityIds.contains(incident.getActivityId())) {
                out.add(incident.getMessage());
            }
        }
        return out;
    }

    /** Разбор JSON-объекта в типизированные переменные процесса. */
    private List<ProcessVariable> parseVariables(String varsJson) {
        List<ProcessVariable> out = new ArrayList<>();
        if (varsJson == null || varsJson.isBlank()) {
            return out;
        }
        Map<String, Object> parsed;
        try {
            parsed = new tools.jackson.databind.ObjectMapper().readValue(varsJson, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("varsJson is not a JSON object: " + varsJson, e);
        }
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            ProcessVariable v = new ProcessVariable();
            v.setName(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Boolean b) {
                v.setType(ProcessVariableType.BOOLEAN);
                v.setValue(String.valueOf(b));
            } else if (value instanceof Integer || value instanceof Long) {
                v.setType(ProcessVariableType.LONG);
                v.setValue(String.valueOf(value));
            } else if (value instanceof Double || value instanceof Float) {
                v.setType(ProcessVariableType.DOUBLE);
                v.setValue(String.valueOf(value));
            } else if (value instanceof List || value instanceof Map) {
                v.setType(ProcessVariableType.JSON);
                v.setValue(String.valueOf(value));
            } else if (value == null) {
                v.setType(ProcessVariableType.STRING);
                v.setValue("");
            } else {
                v.setType(ProcessVariableType.STRING);
                v.setValue(String.valueOf(value));
            }
            out.add(v);
        }
        return out;
    }

    /** Переменные из JSON-строки (пустой объект — пустой список). */
    private List<ProcessVariable> vars(String varsJson) {
        return parseVariables(varsJson);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
