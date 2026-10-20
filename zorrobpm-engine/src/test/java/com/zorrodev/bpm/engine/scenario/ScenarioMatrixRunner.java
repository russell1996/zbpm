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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WO-OPS-27: раннер одного сценария на нашем движке.
 *
 * <p>Прогон: деплой сгенерированной схемы → старт с коллекцией → завершение
 * инстансов MI-контейнера в заданном порядке → НАБЛЮДЕНИЕ состояния в корневом
 * скоупе (в момент, когда «наблюдательная» задача {@code dump} уже создана и
 * ждёт) → завершение наблюдательной задачи → проверка инварианта утечек
 * (WO-OPS-26) по этому инстансу.
 *
 * <p>Наблюдение именно в корневом скоупе — так же, как в оракуле Zeebe 8.5
 * (payload job'а наблюдательной задачи = переменные её скоупа). Это делает
 * сопоставление двух движков честным: сравниваются ОДИН И ТОТ ЖЕ набор
 * переменных, а не «что примерно где-то записалось».
 *
 * <p>Внутренние переменные движка (префикс {@code _}, например {@code _mi_batch_*})
 * из наблюдения исключаются: в Zeebe аналога нет, и это не поведение BPMN,
 * а служебная разметка (WO-OPS-26 §7 фиксирует её как «хранится осознанно»).
 */
public final class ScenarioMatrixRunner {

    private final ProcessDefinitionService processDefinitionService;
    private final RuntimeService runtimeService;
    private final QueryService queryService;
    private final ActivityRepository activityRepository;
    private final VariableRepository variableRepository;
    private final IncidentRepository incidentRepository;
    private final ElementSupport elementSupport;
    private final JdbcTemplate jdbc;

    public ScenarioMatrixRunner(ProcessDefinitionService processDefinitionService,
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
    public ScenarioOutcome run(BpmnScenario scenario) {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = null;
        try {
            ProcessDefinition definition = processDefinitionService.addProcessDefinition(scenario.bpmn());
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definition.getId());
            dto.setVariables(List.of(jsonVar(BpmnScenarioGenerator.COLLECTION_VAR, itemsJson(scenario.items()))));
            pi = runtimeService.startProcessInstance(dto).getId();

            drive(scenario, pi);

            Map<String, String> observed = observe(scenario, pi);
            List<String> incidents = incidentsOf(pi);
            String status = statusOf(pi);
            List<String> leaks = leaksOf(pi, baseline);
            return new ScenarioOutcome(scenario.id(), scenario.container(), scenario.content(),
                    scenario.cardinality(), scenario.orderKey(), status, observed, leaks, incidents, "");
        } catch (RuntimeException e) {
            return new ScenarioOutcome(scenario.id(), scenario.container(), scenario.content(),
                    scenario.cardinality(), scenario.orderKey(),
                    pi == null ? "FAILED" : statusOf(pi),
                    Map.of(), List.of(), pi == null ? List.of() : incidentsOf(pi),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Завершает инстансы в заданном порядке (каждый инстанс — до конца его цепочки). */
    private void drive(BpmnScenario scenario, UUID pi) {
        for (String item : scenario.order()) {
            // Шагов внутри инстанса может быть несколько (содержимое CHAIN) —
            // повторяем, пока у инстанса не останется живых задач.
            for (int guard = 0; guard < scenario.taskElementIds().size() + 2; guard++) {
                List<ActivityEntity> live = liveTasks(pi, scenario).stream()
                        .filter(a -> item.equals(itemOf(pi, a.getId())))
                        .toList();
                if (live.isEmpty()) {
                    break;
                }
                for (ActivityEntity activity : live) {
                    if (scenario.userTaskElementIds().contains(activity.getBpmnElementId())) {
                        runtimeService.completeUserTask(activity.getId(), List.of());
                    } else {
                        runtimeService.completeServiceTask(activity.getId(), List.of());
                    }
                }
            }
        }
    }

    /** Живые (CREATED) задачи сценария — элементы, которые раннер водит. */
    private List<ActivityEntity> liveTasks(UUID pi, BpmnScenario scenario) {
        return activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                .filter(a -> scenario.taskElementIds().contains(a.getBpmnElementId()))
                .toList();
    }

    /**
     * Значение inputElement ({@code item}) для activity.
     *
     * <p>Сначала собственный скоуп activity (MI на задаче: элемент привязан к
     * скоупу самой задачи), затем — цепочка объемлющих скоупов (MI-подпроцесс:
     * элемент лежит внутри инстанса контейнера, {@code item} привязан к скоупу
     * инстанса).
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

    /**
     * Наблюдение состояния: ждём «наблюдательную» задачу, читаем корневой скоуп,
     * затем завершаем её (инстанс должен завершиться).
     */
    private Map<String, String> observe(BpmnScenario scenario, UUID pi) {
        ActivityEntity dump = awaitDump(pi, scenario);
        Map<String, String> observed = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            if (v.getName() != null && v.getName().startsWith("_")) {
                continue; // служебные переменные движка — не часть BPMN-наблюдения
            }
            observed.put(v.getName(), v.getTextValue());
        }
        runtimeService.completeServiceTask(dump.getId(), List.of());
        return observed;
    }

    private ActivityEntity awaitDump(UUID pi, BpmnScenario scenario) {
        for (int i = 0; i < 50; i++) {
            List<ActivityEntity> dumps = activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                    .filter(a -> scenario.dumpElementId().equals(a.getBpmnElementId()))
                    .toList();
            if (!dumps.isEmpty()) {
                return dumps.get(0);
            }
            sleep(50);
        }
        throw new IllegalStateException("dump task '" + scenario.dumpElementId()
                + "' never appeared for scenario " + scenario.id());
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
    private List<String> leaksOf(UUID pi, Set<UUID> baseline) {
        return LeakInvariantChecker.check(jdbc, baseline).stream()
                .filter(v -> pi.equals(v.instanceId()))
                .map(Object::toString)
                .toList();
    }

    private static ProcessVariable jsonVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(value);
        return v;
    }

    private static String itemsJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append("]").toString();
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
