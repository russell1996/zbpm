package com.zorrodev.bpm.engine.scenario.event;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.scheduler.TimerBatchProcessor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-OPS-28 ФАЗА 4: раннер одного сценария среза «события» на нашем движке.
 *
 * <p>Прогон: деплой схемы → старт (обычным вызовом / броском сигнала / срабатыванием
 * таймера) → выполнение СПИСКА ДЕЙСТВИЙ (тот же список, что выполняет оракул Zeebe 8.5) →
 * снятие наблюдения в точке наблюдения → сбор финального состояния инстанса и
 * нарушений инварианта утечек (WO-OPS-26, режим enforce — см. {@code LeakInvariantTestListener}).
 *
 * <p><b>Транзакции.</b> Каждое действие выполняется в СВОЕЙ транзакции
 * ({@code REQUIRES_NEW}) и коммитится: в проде каждый запрос оператора — отдельная
 * транзакция, и таймеры/подписки следующего шага должны видеть committed-состояние.
 * Исключение — {@link EventScenarioAction#FIRE_TIMERS}: вызов
 * {@code TimerBatchProcessor.processBatch()} идёт ВНЕ транзакции, ровно как его
 * вызывает планировщик ({@code TimerScheduler.fireDueTimers} — не транзакционный),
 * иначе {@code FOR UPDATE SKIP LOCKED} держал бы строки джобов и собственный
 * {@code REQUIRES_NEW}-fire того же джоба встал бы в дедлок.
 */
public final class EventScenarioRunner {

    private final ProcessDefinitionService processDefinitionService;
    private final RuntimeService runtimeService;
    private final ActivityService activityService;
    private final DBService dbService;
    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final VariableRepository variableRepository;
    private final IncidentRepository incidentRepository;
    private final TimerBatchProcessor timerBatchProcessor;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    public EventScenarioRunner(ProcessDefinitionService processDefinitionService,
            RuntimeService runtimeService,
            ActivityService activityService,
            DBService dbService,
            ActivityRepository activityRepository,
            ProcessInstanceRepository processInstanceRepository,
            VariableRepository variableRepository,
            IncidentRepository incidentRepository,
            TimerBatchProcessor timerBatchProcessor,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.processDefinitionService = processDefinitionService;
        this.runtimeService = runtimeService;
        this.activityService = activityService;
        this.dbService = dbService;
        this.activityRepository = activityRepository;
        this.processInstanceRepository = processInstanceRepository;
        this.variableRepository = variableRepository;
        this.incidentRepository = incidentRepository;
        this.timerBatchProcessor = timerBatchProcessor;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /** Прогон одного сценария; исключения НЕ проглатываются — они попадают в outcome. */
    public EventScenarioOutcome run(EventScenario scenario) {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbc);
        Map<String, Object> observation = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        UUID pi = null;
        try {
            ProcessDefinition definition = inNewTx(() -> processDefinitionService.addProcessDefinition(scenario.bpmn()));
            UUID definitionId = definition.getId();

            pi = start(scenario, definitionId);

            for (EventScenarioAction action : scenario.actions()) {
                if (pi == null) {
                    pi = findInstance(definitionId);
                }
                execute(scenario, action, pi, observation);
            }
            if (pi == null) {
                pi = findInstance(definitionId);
            }

            observation.putIfAbsent("dumpReached", dumpReached(pi, scenario.dumpElementId()));
            observation.putIfAbsent("vars", pi == null ? Map.of() : rootVars(pi));
            observation.put("liveElements", pi == null ? Map.of() : liveElements(pi));
            observation.put("elementCounts", pi == null ? Map.of() : elementCounts(pi));
            observation.put("instanceStatus", pi == null ? "NO_INSTANCE" : instanceStatus(pi));
            observation.put("instanceCount", countInstances(definitionId));
            observation.put("notes", notes);

            List<String> incidents = pi == null ? List.of() : incidentsOf(pi);
            List<String> leaks = pi == null ? List.of() : leaksOf(pi, baseline);
            String status = pi == null ? "NO_INSTANCE" : instanceStatus(pi);
            return new EventScenarioOutcome(scenario.id(), scenario.family(), scenario.element(),
                    status, observation, leaks, incidents, "");
        } catch (RuntimeException e) {
            return new EventScenarioOutcome(scenario.id(), scenario.family(), scenario.element(),
                    pi == null ? "FAILED" : instanceStatus(pi),
                    observation, List.of(),
                    pi == null ? List.of() : incidentsOf(pi),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Старт инстанса — по способу сценария. */
    private UUID start(EventScenario scenario, UUID definitionId) {
        return switch (scenario.startMode()) {
            case PLAIN -> inNewTx(() -> {
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                dto.setVariables(parseVariables(scenario.varsJson()));
                return runtimeService.startProcessInstance(dto).getId();
            });
            case SIGNAL -> {
                // Бросок сигнала делает САМ сценарий (первое действие списка): если
                // бросить его ещё и здесь, сигнал уйдёт дважды и стартуют ДВА инстанса
                // (проверено прогоном: instanceCount=2 вместо 1).
                yield null;
            }
            case TIMER -> {
                // Таймер старта срабатывает по расписанию; сам его выстрел — отдельное
                // действие сценария (fireTimers), здесь инстанса ещё нет.
                yield null;
            }
        };
    }

    /** Выполнение одного действия сценария. */
    private void execute(EventScenario scenario, EventScenarioAction action, UUID pi,
            Map<String, Object> observation) {
        switch (action.kind()) {
            case EventScenarioAction.COMPLETE -> completeTasks(pi, action);
            case EventScenarioAction.BROADCAST_SIGNAL -> inNewTx(() -> {
                activityService.broadcastSignal(action.target(), parseVariables(action.varsJson()));
                return null;
            });
            case EventScenarioAction.WAIT -> sleep(action.ms());
            case EventScenarioAction.FIRE_TIMERS -> {
                // ВНЕ транзакции — как у планировщика (см. javadoc класса).
                timerBatchProcessor.processBatch();
            }
            case EventScenarioAction.THROW_ERROR -> throwError(pi, action);
            case EventScenarioAction.RESOLVE_INCIDENT -> resolveIncident(pi, action);
            case EventScenarioAction.CANCEL_INSTANCE -> cancelInstance(pi);
            case EventScenarioAction.OBSERVE -> {
                // Точка наблюдения: снимаем состояние ЗДЕСЬ (в этот момент), а не в
                // конце прогона — иначе последующее «complete» наблюдательной задачи
                // скрыло бы сам факт её появления.
                observation.put("dumpReached", dumpReached(pi, scenario.dumpElementId()));
                observation.put("vars", pi == null ? Map.of() : rootVars(pi));
            }
            default -> throw new IllegalArgumentException("unknown action kind: " + action.kind());
        }
    }

    /** Завершение живых задач с указанным id элемента. */
    private void completeTasks(UUID pi, EventScenarioAction action) {
        if (pi == null) {
            return;
        }
        List<ActivityEntity> live = awaitLive(pi, action.target());
        if (live.isEmpty()) {
            return;
        }
        inNewTx(() -> {
            for (ActivityEntity activity : live) {
                runtimeService.completeServiceTask(activity.getId(), parseVariables(action.varsJson()));
            }
            return null;
        });
    }

    /** Бросок BPMN-ошибки из сервисной задачи. */
    private void throwError(UUID pi, EventScenarioAction action) {
        if (pi == null) {
            return;
        }
        List<ActivityEntity> live = awaitLive(pi, action.target());
        if (live.isEmpty()) {
            return;
        }
        inNewTx(() -> {
            for (ActivityEntity activity : live) {
                ThrowErrorDTO dto = new ThrowErrorDTO();
                dto.setErrorCode(action.code());
                dto.setVariables(parseVariables(action.varsJson()));
                runtimeService.throwServiceTaskError(activity.getId(), dto);
            }
            return null;
        });
    }

    /** Разбор первого открытого инцидента инстанса. */
    private void resolveIncident(UUID pi, EventScenarioAction action) {
        if (pi == null) {
            return;
        }
        List<IncidentEntity> incidents = incidentsOf(pi, incidentRepository.findAll());
        if (incidents.isEmpty()) {
            return;
        }
        UUID incidentId = incidents.get(0).getId();
        inNewTx(() -> {
            runtimeService.resolveIncident(incidentId, parseVariables(action.varsJson()));
            return null;
        });
    }

    /**
     * Операторская отмена инстанса — те же вызовы, что делает REST-слой
     * ({@code ProcessInstanceRuntimeOperationsImpl#cancelProcessInstance}, строки 128–141):
     * сначала гасятся живые activity, затем чистятся таймеры/подписки, затем гасится
     * инстанс. Низкоуровневый {@code DBService#cancelProcessInstance} сам по себе activity
     * не гасит (это зафиксировано в allowlist инварианта как WO-OPS-35), поэтому
     * вызывается полная последовательность REST-слоя.
     */
    private void cancelInstance(UUID pi) {
        if (pi == null) {
            return;
        }
        inNewTx(() -> {
            dbService.lockProcessInstance(pi);
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
            return null;
        });
    }

    /** Живая задача с указанным id элемента (ждёт появления). */
    private List<ActivityEntity> awaitLive(UUID pi, String elementId) {
        for (int i = 0; i < 40; i++) {
            List<ActivityEntity> live = activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                    .filter(a -> elementId.equals(a.getBpmnElementId()))
                    .toList();
            if (!live.isEmpty()) {
                return live;
            }
            sleep(50);
        }
        return List.of();
    }

    /** Есть ли живая задача точки наблюдения. */
    private boolean dumpReached(UUID pi, String dumpElementId) {
        if (pi == null || dumpElementId == null || dumpElementId.isBlank()) {
            return false;
        }
        return activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                .anyMatch(a -> dumpElementId.equals(a.getBpmnElementId()));
    }

    /** Корневой скоуп инстанса — то же, что payload job'а наблюдательной задачи в Zeebe. */
    private Map<String, String> rootVars(UUID pi) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            if (v.getName() != null && v.getName().startsWith("_")) {
                continue;
            }
            out.put(v.getName(), v.getTextValue());
        }
        return out;
    }

    /** Живые элементы инстанса: id элемента → статус (для сравнения с job'ами оракула). */
    private Map<String, Object> liveElements(UUID pi) {
        Map<String, Object> out = new LinkedHashMap<>();
        activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
                .forEach(a -> out.merge(a.getBpmnElementId(), a.getStatus().name(), (a1, a2) -> a1 + "+" + a2));
        return out;
    }

    /**
     * Сколько раз инстанцирован каждый элемент инстанса (id элемента → число activity-строк).
     *
     * <p>Это аналог «сколько job'ов этого элемента увидел оракул»: по нему видно,
     * СКОЛЬКО РАЗ сработала граница (одна на всё тело MI или по одной на инстанс MI) —
     * ровно тот вопрос, который стоит за расхождением ENG-3.
     */
    private Map<String, Object> elementCounts(UUID pi) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .forEach(a -> counts.merge(a.getBpmnElementId(), 1, Integer::sum));
        return new LinkedHashMap<>(counts);
    }

    /** Статус инстанса: COMPLETED / CANCELLED / NOT_COMPLETED. */
    private String instanceStatus(UUID pi) {
        ProcessInstanceEntity instance = processInstanceRepository.findById(pi).orElse(null);
        if (instance == null) {
            return "NOT_FOUND";
        }
        if (instance.getCompletedAt() != null) {
            return instance.isCancelled() ? "CANCELLED" : "COMPLETED";
        }
        return "NOT_COMPLETED";
    }

    /** Инстансы, созданные этим деплоем (для счётчика — циклический стартовый таймер). */
    private int countInstances(UUID definitionId) {
        return processInstanceRepository
                .findAll(ProcessInstanceRepository.byProcessDefinitionId(definitionId)).size();
    }

    /** Первый (самый ранний) инстанс этого деплоя. */
    private UUID findInstance(UUID definitionId) {
        return processInstanceRepository.findAll(ProcessInstanceRepository.byProcessDefinitionId(definitionId))
                .stream()
                .min(Comparator.comparing(ProcessInstanceEntity::getStartedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProcessInstanceEntity::getId)
                .orElse(null);
    }

    /** Сообщения инцидентов инстанса. */
    private List<String> incidentsOf(UUID pi) {
        List<IncidentEntity> all = incidentRepository.findAll();
        return incidentsOf(pi, all).stream().map(IncidentEntity::getMessage).toList();
    }

    private List<IncidentEntity> incidentsOf(UUID pi, List<IncidentEntity> all) {
        java.util.Set<UUID> activityIds = activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .map(ActivityEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        return all.stream()
                .filter(i -> i.getCompletedAt() == null)
                .filter(i -> activityIds.contains(i.getActivityId()))
                .toList();
    }

    /** Нарушения инварианта утечек по этому инстансу (только «свои»). */
    private List<String> leaksOf(UUID pi, LeakInvariantChecker.Baseline baseline) {
        return LeakInvariantChecker.check(jdbc, baseline).own().stream()
                .filter(v -> pi.equals(v.instanceId()))
                .map(Object::toString)
                .toList();
    }

    /** Разбор JSON-объекта в типизированные переменные процесса. */
    private List<ProcessVariable> parseVariables(String varsJson) {
        List<ProcessVariable> out = new ArrayList<>();
        if (varsJson == null || varsJson.isBlank() || "{}".equals(varsJson)) {
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
                v.setValue(toJson(value));
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

    /** Значение переменной — честный JSON (String.valueOf(List) дал бы "[a, b]"). */
    private String toJson(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot serialize variable value: " + value, e);
        }
    }

    /** Действие в своей транзакции (REQUIRES_NEW) — коммит после шага. */
    private <T> T inNewTx(java.util.function.Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
