package com.zorrodev.bpm.engine.scenario;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WO-OPS-28 ФАЗА 3: раннер одного сценария среза «контейнеры» на нашем движке.
 *
 * <p>Отличие от раннера среза F1 ({@link ScenarioMatrixRunner}): водит не по
 * MI-инстансам, а по СПИСКУ ШАГОВ ({@link ContainerScenario.Step}) — так сценарий
 * может (а) завершить задачу ДОЧЕРНЕГО процесса (call activity), (б) записать
 * переменные при завершении, (в) завершить РОВНО ОДИН инстанс MI-цикла
 * (completionCondition), (г) дождаться задачи, которая появляется ПОСЛЕ
 * броска сигнала (event sub-process).
 *
 * <p>Тот же список шагов выполняет и оракул Zeebe 8.5 — сравнение честное.
 *
 * <p>Наблюдение — то же, что в срезе F1: «наблюдательная» задача {@code dump}
 * в конце; читаем переменные КОРНЕВОГО скоупа инстанса и завершаем её.
 */
public final class ContainerScenarioRunner {

    /** Сколько раз ждать появления нужной activity (50 мс на попытку). */
    private static final int WAIT_TRIES = 60;

    private final ProcessDefinitionService processDefinitionService;
    private final RuntimeService runtimeService;
    private final QueryService queryService;
    private final ActivityRepository activityRepository;
    private final VariableRepository variableRepository;
    private final IncidentRepository incidentRepository;
    private final ElementSupport elementSupport;
    private final JdbcTemplate jdbc;

    public ContainerScenarioRunner(ProcessDefinitionService processDefinitionService,
            RuntimeService runtimeService,
            QueryService queryService,
            ActivityRepository activityRepository,
            VariableRepository variableRepository,
            IncidentRepository incidentRepository,
            ElementSupport elementSupport,
            JdbcTemplate jdbc) {
        this.processDefinitionService = processDefinitionService;
        this.runtimeService = runtimeService;
        this.queryService = queryService;
        this.activityRepository = activityRepository;
        this.variableRepository = variableRepository;
        this.incidentRepository = incidentRepository;
        this.elementSupport = elementSupport;
        this.jdbc = jdbc;
    }

    /** Прогон одного сценария; исключения НЕ проглатываются — они попадают в outcome. */
    public ScenarioOutcome run(ContainerScenario scenario) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = null;
        try {
            // Дочерние ресурсы (call activity) — ОТДЕЛЬНЫЙ деплой: у нас и в Zeebe
            // дочерний процесс не может лежать в том же ресурсе (сканер отвергает
            // несколько <process> в одном ресурсе).
            for (String child : scenario.childBpmn()) {
                deploy(child);
            }
            ProcessDefinition definition = deploy(scenario.bpmn());
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definition.getId());
            dto.setVariables(startVariables(scenario));
            pi = runtimeService.startProcessInstance(dto).getId();

            drive(scenario, pi);

            Observation observation = observe(scenario, pi);
            List<String> incidents = incidentsOf(pi);
            // Статус инстанса и статус ДОСТИЖЕНИЯ точки наблюдения — разные вещи:
            // инстанс может завершиться, не дойдя до наблюдательной задачи
            // (прерванный поток), и это наблюдаемый факт, а не сбой прогона.
            String status = observation.dumpReached() ? statusOf(pi) : "NO_DUMP";
            List<String> leaks = leaksOf(pi, baseline);
            return new ScenarioOutcome(scenario.id(), scenario.base().container(), scenario.content(),
                    scenario.cardinality(), "", status, observation.vars(), leaks, incidents, "");
        } catch (DeployRefused e) {
            // Отказ при деплое — это НАБЛЮДАЕМЫЙ ФАКТ (у нас так и у оракула),
            // а не сбой прогона: сравнивать нечего, но факт фиксируется.
            return new ScenarioOutcome(scenario.id(), scenario.base().container(), scenario.content(),
                    scenario.cardinality(), "", "DEPLOY_REJECTED", Map.of(), List.of(), List.of(),
                    e.getMessage());
        } catch (RuntimeException e) {
            return new ScenarioOutcome(scenario.id(), scenario.base().container(), scenario.content(),
                    scenario.cardinality(), "",
                    pi == null ? "FAILED" : statusOf(pi),
                    Map.of(), pi == null ? List.of() : leaksOf(pi, baseline),
                    pi == null ? List.of() : incidentsOf(pi),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Отказ при деплое модели (наш движок отвергает неподдерживаемые конструкции). */
    static final class DeployRefused extends RuntimeException {
        DeployRefused(String message) {
            super(message);
        }
    }

    /**
     * Деплой ресурса. Отказ движка (ApiException со стабильным кодом —
     * например {@code UNSUPPORTED_MULTI_INSTANCE_CONTAINER}) — это НАБЛЮДАЕМЫЙ
     * ФАКТ, а не сбой прогона: он попадает в outcome отдельным статусом.
     */
    private ProcessDefinition deploy(String bpmn) {
        try {
            return processDefinitionService.addProcessDefinition(bpmn);
        } catch (com.zorrodev.bpm.contract.exception.ApiException e) {
            throw new DeployRefused(e.getCode() + ": " + e.getMessage());
        }
    }

    /** Стартовые переменные сценария (коллекция MI-цикла — одна переменная JSON). */
    private List<ProcessVariable> startVariables(ContainerScenario scenario) {
        String raw = jsonArrayOf(scenario.items());
        if (raw == null) {
            return List.of();
        }
        ProcessVariable v = new ProcessVariable();
        v.setName(BpmnScenarioGenerator.COLLECTION_VAR);
        v.setType(ProcessVariableType.JSON);
        v.setValue(raw);
        return List.of(v);
    }

    /** JSON-массив коллекции MI-цикла (или null, если коллекции нет). */
    private String jsonArrayOf(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append("]").toString();
    }

    /**
     * Ведение сценария по списку шагов.
     *
     * <p>Шаг: дождаться живых activity с его element id (в дереве инстанса,
     * включая дочерние инстансы call activity), при необходимости отфильтровать
     * по значению MI-цикла {@code item} и завершить их, записав переменные шага.
     *
     * <p>Если за отведённое время нужная activity не появилась — шаг считается
     * НЕДОСТИГНУТЫМ (это наблюдаемый факт: например, прерывающий event
     * sub-process гасит основной поток, и задача «after» не создаётся вовсе).
     */
    private void drive(ContainerScenario scenario, UUID pi) {
        for (ContainerScenario.Step step : scenario.steps()) {
            List<ActivityEntity> live = awaitLive(pi, step);
            for (ActivityEntity activity : live) {
                if (scenario.userTaskElementIds().contains(activity.getBpmnElementId())) {
                    runtimeService.completeUserTask(activity.getId(), stepVariables(step));
                } else {
                    runtimeService.completeServiceTask(activity.getId(), stepVariables(step));
                }
            }
        }
    }

    /** Живые activity шага (element id + необязательный фильтр по item). */
    private List<ActivityEntity> awaitLive(UUID pi, ContainerScenario.Step step) {
        for (int i = 0; i < WAIT_TRIES; i++) {
            List<ActivityEntity> live = liveMatching(pi, step);
            if (!live.isEmpty()) {
                return live;
            }
            sleep(50);
        }
        return List.of();
    }

    /** Живые activity с нужным element id в дереве инстанса (родитель + дочерние). */
    private List<ActivityEntity> liveMatching(UUID pi, ContainerScenario.Step step) {
        Set<UUID> tree = instanceTree(pi);
        return activityRepository.findAll().stream()
                .filter(a -> tree.contains(a.getProcessInstanceId()))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                .filter(a -> step.elementId().equals(a.getBpmnElementId()))
                .filter(a -> step.anyInstance() || step.item().equals(itemOf(pi, a.getId())))
                .toList();
    }

    /** Инстанс и его дочерние инстансы (call activity). */
    private Set<UUID> instanceTree(UUID pi) {
        Set<UUID> tree = new LinkedHashSet<>();
        tree.add(pi);
        List<UUID> children = jdbc.queryForList(
                "select pi2.id from process_instances pi2 where pi2.parent_activity_id in "
                        + "(select act.id from activities act where act.process_instance_id = ?)",
                UUID.class, pi);
        tree.addAll(children);
        return tree;
    }

    /**
     * Значение переменной MI-цикла {@code item} для activity: собственный скоуп,
     * затем цепочка объемлющих скоупов (ближайший выигрывает).
     */
    private String itemOf(UUID pi, UUID activityId) {
        String own = itemInScope(pi, activityId);
        if (own != null) {
            return own;
        }
        List<UUID> chain = elementSupport.enclosingScopeChain(pi, activityId);
        for (int i = chain.size() - 1; i >= 0; i--) {
            String scoped = itemInScope(pi, chain.get(i));
            if (scoped != null) {
                return scoped;
            }
        }
        return null;
    }

    private String itemInScope(UUID pi, UUID scopeId) {
        return variableRepository.findByProcessInstanceIdAndScopeId(pi, scopeId).stream()
                .filter(v -> BpmnScenarioGenerator.INPUT_ELEMENT.equals(v.getName()))
                .map(v -> unquote(v.getTextValue()))
                .findFirst()
                .orElse(null);
    }

    /** Переменные шага (JSON-объект → типизированные переменные). */
    private List<ProcessVariable> stepVariables(ContainerScenario.Step step) {
        String json = step.varsJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return List.of();
        }
        List<ProcessVariable> out = new ArrayList<>();
        try {
            Map<String, Object> parsed = new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                ProcessVariable v = new ProcessVariable();
                v.setName(entry.getKey());
                v.setType(ProcessVariableType.STRING);
                v.setValue(entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                out.add(v);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("step varsJson is not a JSON object: " + json, e);
        }
        return out;
    }

    /** Наблюдение у точки наблюдения: переменные корневого скоупа + факт достижения. */
    private record Observation(Map<String, String> vars, boolean dumpReached) {
    }

    /**
     * Наблюдение: ждём «наблюдательную» задачу, читаем корневой скоуп, завершаем её.
     * Если задача не появилась — сценарий НЕ дошёл до точки наблюдения (NO_DUMP).
     */
    private Observation observe(ContainerScenario scenario, UUID pi) {
        ActivityEntity dump = null;
        for (int i = 0; i < WAIT_TRIES && dump == null; i++) {
            dump = activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                    .filter(a -> scenario.dumpElementId().equals(a.getBpmnElementId()))
                    .findFirst().orElse(null);
            if (dump == null) {
                sleep(50);
            }
        }
        if (dump == null) {
            return new Observation(Map.of(), false);
        }
        Map<String, String> observed = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            if (v.getName() != null && v.getName().startsWith("_")) {
                continue; // служебные переменные движка (_mi_batch_* и пр.) — не BPMN-наблюдение
            }
            observed.put(v.getName(), v.getTextValue());
        }
        runtimeService.completeServiceTask(dump.getId(), List.of());
        return new Observation(observed, true);
    }

    private String statusOf(UUID pi) {
        var instance = queryService.getProcessInstance(pi);
        if (instance == null) {
            return "NOT_FOUND";
        }
        return instance.getCompletedAt() != null ? "COMPLETED" : "NOT_COMPLETED";
    }

    private List<String> incidentsOf(UUID pi) {
        Set<UUID> activityIds = activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
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

    /** Нарушения инварианта утечек (WO-OPS-26) по этому инстансу. */
    private List<String> leaksOf(UUID pi, LeakInvariantChecker.Baseline baseline) {
        return LeakInvariantChecker.check(jdbc, baseline).own().stream()
                .filter(v -> pi.equals(v.instanceId()))
                .map(Object::toString)
                .toList();
    }

    private static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
