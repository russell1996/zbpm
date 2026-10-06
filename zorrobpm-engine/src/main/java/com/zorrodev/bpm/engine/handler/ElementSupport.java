package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared utility methods for BPMN expression resolution, variable mapping, and type conversion.
 * Injected by both {@code ActivityServiceImpl} and {@code MultiInstanceExecutor} to avoid duplication.
 */
@Slf4j
@Component
public class ElementSupport {

    private final DBService dbService;
    private final ScriptService scriptService;
    /**
     * WO-ENG-20 (N09): прямое {@code FeelEngineApi}-поле заменено единой точкой входа
     * с лимитами — {@code =expr}-резолв ниже идёт через общий пул/timeout WO-REL-46,
     * а не напрямую в caller-thread без ограничения.
     */
    private final FeelBudget feelBudget;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Business timezone for interpreting zone-naive FEEL date/time values.
     * Configured via {@code zorrobpm.business-timezone} (default {@code Asia/Almaty}).
     * WO-ENG-4: zone-naive LocalDateTime/LocalDate is interpreted in this zone rather than UTC.
     *
     * WO-QW-1 A-C-5e: final + explicit constructor (was non-final with
     * {@code @RequiredArgsConstructor}, so the field silently stayed null outside
     * Spring). The @Value default below is the single source of the default.
     */
    private final ZoneId businessZone;

    /**
     * WO-ENG-30: compat flag for the WO-ENG-29 strict behaviour. Default {@code false}
     * so already-deployed models referencing an OPTIONAL variable keep the legacy silent
     * {@code ""} instead of parking on an incident on the first real run after rollout.
     * Operators opt into the strict (Zeebe-closer) behaviour explicitly per installation.
     */
    private final boolean strictMissing;

    public ElementSupport(DBService dbService, ScriptService scriptService,
            FeelBudget feelBudget, tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${zorrobpm.business-timezone:Asia/Almaty}") ZoneId businessZone,
            @Value("${zorrobpm.engine.io-mapping.strict-missing:false}") boolean strictMissing) {
        this.dbService = dbService;
        this.scriptService = scriptService;
        this.feelBudget = feelBudget;
        this.objectMapper = objectMapper;
        this.businessZone = businessZone;
        this.strictMissing = strictMissing;
    }

    /**
     * WO-REL-59 + WO-REL-63: единый порядок захвата instance→activity — тот же,
     * что у cancel-пути ({@code ProcessInstanceRuntimeOperationsImpl}:
     * {@code lockProcessInstance} → {@code cancelActiveActivities}).
     *
     * <p>Единственный способ взять activity-lock в этом движке. Прежний
     * {@code lockAndReload} (activity-only) удалён в WO-REL-63: после перевода
     * последних путей на этот метод у него не осталось ни одного
     * продакшн-вызова, а сам он — ровно та ловушка, которой оба раза ловился
     * ABBA-дедлок с отменой.
     *
     * <p>Почему activity-first неверен: {@code DBService.getActivityForUpdate}
     * на PostgreSQL даёт {@code FOR UPDATE OF} с одним алиасом и лочит ТОЛЬКО
     * строку activity (см. javadoc {@code ActivityRepository.findByIdForUpdate}
     * и {@code Rel59SqlProbePgIT}). Дальше путь исполнения в конце flow делает
     * {@code completeProcessInstance} — UPDATE строки {@code process_instances},
     * то есть просит ТОТ ЖЕ instance-row-lock, который уже держит отмена. Отмена
     * же берёт instance-lock первым и затем построчно UPDATE'ит activity-строки.
     * Получается activity→instance против instance→activity — классический
     * ABBA, проигравший получает {@code ERROR: deadlock detected} (SQLState
     * 40P01) через {@code deadlock_timeout} (~1с). Воспроизведено на реальном PG:
     * {@code Rel59CompleteCancelDeadlockPgIT} (complete) и
     * {@code Rel63RemainingAbbaDeadlockPgIT} (таймер / сообщение / граничное
     * событие) — 5–7 раундов deadlock на дереве до фикса, 0 после. С фиксом
     * второй участник просто ждёт коммита первого — сериализация вместо
     * deadlock.
     *
     * <p>Механика — прецедент {@code IncidentService.resolveIncident}:
     * plain read (нужен только processInstanceId) → instance-lock →
     * {@code getActivityForUpdate} (свежесть даёт сам захват: cancel пишет
     * activity-строки только под instance-lock; row-lock держится до коммита
     * той же транзакции — паттерн WO-ENG-19). Инвариант WO-REL-30 (ровно один
     * SELECT FOR UPDATE на activity) сохранён; добавлен ровно один FOR UPDATE на
     * instance — он и есть недостающая первая половина порядка.
     *
     * <p>Требует внешней транзакции ({@code getActivity} и
     * {@code lockProcessInstance} не открывают свои — JOIN-аннотация стоит на
     * {@code getActivityForUpdate}). Все живые вызывающие — доменные методы с
     * классовым {@code @Transactional} ({@code ActivityServiceImpl},
     * {@code CompletionService}, {@code EventTrigger}) — либо {@code
     * TimerJobExecutor.fire} с {@code REQUIRES_NEW}, так что в бою условие
     * выполняется.
     */
    public Activity lockInstanceFirst(UUID activityId) {
        Activity activity = dbService.getActivity(activityId);
        dbService.lockProcessInstance(activity.getProcessInstanceId());
        return dbService.getActivityForUpdate(activityId);
    }

    // ─── User task helpers ──────────────────────────────────────────────

    public String extractAssignee(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssignee)
            .orElse(null);
    }

    public String resolveAssignee(UUID processInstanceId, BpmnElementModel element) {
        String raw = extractAssignee(element);
        return resolveExpression(raw, processInstanceId);
    }

    public String resolveCandidateGroups(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateGroups)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    /**
     * WO-IN-3: кандидаты-ПОЛЬЗОВАТЕЛИ, зеркало {@link #resolveCandidateGroups} — та же выжимка
     * атрибута, то же разрешение выражения (в т.ч. {@code ${переменная}}), тот же null на
     * отсутствующем/пустом значении. Разница только в том, что значение уходит в таблицу
     * кандидатов (роль USER), а не в колонку: до этого WO атрибут парсился в BPMN-модель и там
     * и оставался (E-IN2-1), поэтому фильтровать по нему было нечем.
     */
    public String resolveCandidateUsers(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateUsers)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    public String resolveDueDate(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getDueDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    public String resolveFollowUpDate(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getFollowUpDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    // ─── Service task helpers ─────────────────────────────────────────

    /**
     * WO-C8-9: resolves {@code zeebe:jobPriorityDefinition} to an Integer job priority
     * (activation-order hint, delivered to the worker via JobDetailModel).
     * WO-C8-13 (A-1): the element is jobPriorityDefinition (priorityDefinition lives only on
     * user tasks); precedence is task value, then process-level default, then null.
     * Broken values resolve to null — never an exception or incident, same call as
     * WO-C8-8 made for broken dates (informational construct, must not break execution).
     */
    public Integer resolvePriority(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            raw = Optional.ofNullable(element.getProcessDefinition())
                .map(BpmnProcessDefinitionModel::getDefaultJobPriority)
                .orElse(null);
        }
        if (raw == null || raw.isBlank()) return null;
        String resolved = resolveExpression(raw, processInstanceId);
        if (resolved == null) return null;
        try {
            // WO-QW-6 (NEW3-08): FEEL отдаёт целые с масштабом ("5.00" после
            // WO-ENG-27, "5.0" от Double до этого) — parseInt на таком падает.
            // intValueExact: целые с лишними нулями парсятся, настоящая дробь
            // бросает ArithmeticException → тот же null-контракт, что раньше.
            return new java.math.BigDecimal(resolved.trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            log.warn("jobPriorityDefinition '{}' resolved to non-integer '{}' — ignoring", raw, resolved);
            return null;
        }
    }

    /**
     * WO-C8-30: resolves {@code zeebe:priorityDefinition/@priority} of a user task to
     * an Integer task priority, evaluated at activation (docs: "Expressions are
     * evaluated when the user task is activated"). Absent/blank → docs default 50
     * ("If no value is provided, the default value is {@code 50}").
     *
     * <p>Unlike {@link #resolvePriority} (job dispatch hint — broken values resolve
     * to null, never an incident), a broken/out-of-range user-task priority THROWS
     * {@code EngineException} with the element, the raw value and the valid range:
     * WO-C8-30 demands an explicit incident, never a silent default. Callers turn
     * this into {@code errorActivity + createIncident} and halt activation.
     */
    public int resolveUserTaskPriorityOrThrow(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        String resolved = resolveExpression(raw, processInstanceId);
        Integer value = null;
        if (resolved != null && !resolved.isBlank()) {
            try {
                // WO-QW-6 (NEW3-08): см. resolvePriority выше — та же замена
                // parseInt → BigDecimal.intValueExact (целая гарантия 0–100
                // ниже не меняется, "5.50" по-прежнему явная ошибка).
                value = new java.math.BigDecimal(resolved.trim()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                value = null;
            }
        }
        if (value == null || value < 0 || value > 100) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "User task '" + element.getId() + "' has a broken priorityDefinition '" + raw + "'"
                    + (resolved != null && !resolved.equals(raw) ? " (resolved to '" + resolved + "')" : "")
                    + " — priority must be an integer between 0 and 100");
        }
        return value;
    }

    // ─── Expression resolution ──────────────────────────────────────────

    /**
     * Resolves a raw BPMN expression string against process instance variables.
     * - ${var} → extract var name, look up in variables
     * - =expr → evaluate as FEEL expression
     * - plain string → return as-is (literal)
     */
    public String resolveExpression(String raw, UUID processInstanceId) {
        if (raw == null || raw.isBlank()) return null;

        if (raw.startsWith("${") && raw.endsWith("}")) {
            String varName = raw.substring(2, raw.length() - 1).trim();
            Map<String, Object> vars = variablesToMap(processInstanceId);
            Object val = vars.get(varName);
            if (val == null) {
                log.warn("variable '{}' not found in instance {}, returning null", varName, processInstanceId);
                return null;
            }
            return val.toString();
        }

        if (raw.startsWith("=")) {
            Map<String, Object> vars = variablesToMap(processInstanceId);
            // WO-ENG-20: через общий бюджет — timeout/bulkhead вместо прямого вызова
            // в caller-thread. Контракт неуспеха прежний: warn + null.
            EvaluationResult result = feelBudget.evaluateExpression(raw.substring(1), vars);
            if (!result.isSuccess()) {
                log.warn("FEEL expression '{}' failed in instance {}: {}", raw, processInstanceId, result.failure());
                return null;
            }
            Object val = result.result();
            return val != null ? val.toString() : null;
        }

        return raw;
    }

    public Map<String, Object> variablesToMap(UUID processInstanceId) {
        List<ProcessVariable> vars = dbService.getVariables(processInstanceId);
        Map<String, Object> map = new HashMap<>();
        for (ProcessVariable v : vars) {
            map.put(v.getName(), v.getValue());
        }
        return map;
    }

    // ─── Service task helpers ───────────────────────────────────────────

    public int serviceTaskRetries(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ext -> ext.getRetries())
            .orElse(3);
    }

    /** WO-EVT-9: stable job identifier from the BPMN model (zeebe:taskDefinition type analog). */
    public String serviceTaskJob(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getJob)
            .orElse(null);
    }

    /**
     * WO-C8-11: start execution listeners of a service task, in declaration order
     * (already filtered to {@code eventType="start"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> serviceTaskStartListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getStartListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-11b: end execution listeners of a service task, in declaration order
     * (already filtered to {@code eventType="end"} at parse time). Empty when absent —
     * callers must not touch end-listener state for such elements.
     */
    public List<ListenerModel> serviceTaskEndListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getEndListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    // ─── User task helpers ──────────────────────────────────────────────

    /**
     * WO-C8-21: creating task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="creating"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCreatingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCreatingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-24: completing task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="completing"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCompletingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCompletingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: assigning task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="assigning"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskAssigningListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssigningListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: updating task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="updating"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskUpdatingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getUpdatingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: canceling task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="canceling"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCancelingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCancelingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    // ─── Listener helpers ─────────────────────────────────────────────

    /**
     * WO-C8-25: start execution listeners of a gateway/event element, in declaration
     * order (already filtered to {@code eventType="start"} at parse time). Empty when
     * absent — callers must not touch listener state for such elements. Separate reader
     * from {@link #serviceTaskStartListeners} ON PURPOSE (see
     * {@code BpmnElementExtensionModel.elementStartListeners}).
     */
    public List<ListenerModel> elementStartListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getElementStartListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-21r2: durable retry budget of one listener job — the model value, default 3
     * from the docs when the attribute is absent. Shared by all three listener kinds
     * (start/end/creating); callers set it wherever they set the phase index.
     */
    public int listenerBudget(ListenerModel listener) {
        return listener.retries() != null ? listener.retries() : 3;
    }

    // ─── IO mapping ────────────────────────────────────────────────────

    public void applyIoMappings(UUID processInstanceId, UUID activityId, BpmnElementModel element, boolean inputs) {
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        if (io == null) {
            return;
        }
        List<IoMappingExtensionModel.Mapping> mappings = inputs ? io.getInputs() : io.getOutputs();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        // WO-DIFF-1 п.2: mappings see the element's own scope PLUS every enclosing
        // sub-process scope down to root (nearest-wins) — not just root+own.
        List<ProcessVariable> variables = visibleVariables(processInstanceId, activityId);
        List<ProcessVariable> results = new ArrayList<>();
        for (IoMappingExtensionModel.Mapping mapping : mappings) {
            ProcessVariable result = evaluateMapping(mapping, variables);
            if (result != null) {
                results.add(result);
            }
        }
        if (!results.isEmpty()) {
            dbService.setVariables(processInstanceId, inputs ? activityId : null, results);
            log.info("{}: Applied {} {} mapping(s) at {} (scope {})", processInstanceId, results.size(), inputs ? "input" : "output", element.getId(), inputs ? activityId : "root");
        }
    }

    /**
     * WO-DIFF-1 п.2: the variable context an ioMapping source evaluates against —
     * process root overlaid with the chain of enclosing sub-process scopes,
     * outermost first, the element's own scope last (nearest-wins for duplicate
     * names). Elements with no enclosing scope get exactly the historical
     * root+own merged view (same single repository read, zero behaviour change).
     * Reuses the existing merged {@code getVariables} reads only — no new
     * repository methods (the variable-ownership boundary stays untouched).
     */
    public List<ProcessVariable> visibleVariables(UUID processInstanceId, UUID activityId) {
        List<UUID> enclosing = enclosingScopeChain(processInstanceId, activityId);
        if (enclosing.isEmpty()) {
            return dbService.getVariables(processInstanceId, activityId);
        }
        Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariables(processInstanceId)) {
            merged.put(v.getName(), v);
        }
        for (UUID scopeId : enclosing) {
            for (ProcessVariable v : dbService.getVariables(processInstanceId, scopeId)) {
                merged.put(v.getName(), v);
            }
        }
        for (ProcessVariable v : dbService.getVariables(processInstanceId, activityId)) {
            merged.put(v.getName(), v);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * WO-DIFF-1 п.2: ids of the sub-process container activities enclosing the
     * given activity, outermost first, own scope excluded. Walked through the
     * token parent chain ({@code scopeActivityId} marks "inside this container");
     * null-safe ({@code findToken}) so a stale token reference yields an empty
     * chain instead of an exception (WO-REL-30 B-4 philosophy).
     */
    public List<UUID> enclosingScopeChain(UUID processInstanceId, UUID activityId) {
        com.zorrodev.bpm.engine.dto.Activity activity = dbService.getActivity(activityId);
        if (activity == null || activity.getToken() == null) {
            return List.of();
        }
        List<UUID> chain = new ArrayList<>();
        java.util.Optional<com.zorrodev.bpm.engine.dto.Token> token =
            dbService.findToken(activity.getToken());
        while (token.isPresent()) {
            UUID scopeId = token.get().getScopeActivityId();
            if (scopeId != null && !scopeId.equals(activityId) && !chain.contains(scopeId)) {
                chain.add(scopeId);
            }
            UUID parentId = token.get().getParentId();
            token = parentId == null ? java.util.Optional.empty() : dbService.findToken(parentId);
        }
        // Walked innermost-first; callers overlay outermost-first (nearest-wins).
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * WO-C8-34 (CR-02/CR-07): narrows an activity list to one execution scope.
     * An activity belongs to {@code scopeActivityId} when the scope container is
     * on its enclosing chain — i.e. its token is the scope token or a descendant
     * of it (parallel branches inside the scope fork child tokens, so a plain
     * token-equality check would miss the siblings; the chain walk covers them).
     * The container activity itself is NOT included (its own scope is the parent).
     * Used by terminate-in-subprocess (cancel only this scope) and by
     * compensation-throw candidate filtering (compensate only this scope).
     */
    public List<Activity> filterActivitiesInScope(
            UUID processInstanceId, List<Activity> activities, UUID scopeActivityId) {
        List<Activity> inScope = new ArrayList<>();
        for (Activity activity : activities) {
            if (activity == null || activity.getId() == null) {
                continue;
            }
            if (enclosingScopeChain(processInstanceId, activity.getId()).contains(scopeActivityId)) {
                inScope.add(activity);
            }
        }
        return inScope;
    }

    /**
     * Evaluates one io-mapping {@code source} (FEEL, optional leading '=') against the given
     * variables and converts the result into a {@link ProcessVariable} named {@code target}.
     * Returns {@code null} for a malformed mapping (missing source/target) — callers skip it.
     * <p>Shared by {@link #applyIoMappings} (activity-scoped writes) and WO-ENG-11 call-activity
     * mappings: input mappings seed a not-yet-created child instance, output mappings override the
     * propagateAllChildVariables toggle — same evaluation pattern, different write scope.</p>
     * <p>WO-ENG-29: evaluation goes through the native FEEL API (same engine, same typed
     * context as the DMN-eval path — {@code FeelBudget}/{@code FeelEngineApi}, not the
     * lossy JSR-223 wrapper) so «evaluation failure» is distinguishable from «honest
     * null»: a failed evaluation (e.g. source references a variable absent from the job
     * result) throws {@link com.zorrodev.bpm.engine.service.FeelEvaluationException}
     * (incident path at callers), while a legitimate FEEL {@code null} (explicit
     * {@code =null} literal) still returns a null-valued variable as before, no
     * exception, no incident. Callers must NOT catch this into a silent default —
     * the input path relies on {@code ActivityServiceImpl.execute()}'s element-failure
     * catch → {@code incidentService.raiseIncident(...)}, the output path on
     * {@code CompletionService}'s symmetric catch in the service-task tail.</p>
     * <p>WO-ENG-30: the «variable not found» case (suppressed failures + null result)
     * throws only when {@code zorrobpm.engine.io-mapping.strict-missing=true}. With the
     * default {@code false} already-deployed models keep the pre-ENG-29 silent
     * {@code ""} behaviour (plus a WARN carrying source/target for future inventory
     * by log grep, no DB access needed). Default is {@code false} NOT because strict
     * is wrong — it is the correct behaviour — but because rolling out strict without
     * an inventory of live models parks them on incidents silently. A HARD evaluation
     * failure ({@code !isSuccess}, e.g. syntax errors — the Zeebe fix class) always
     * throws regardless of the flag: that is a genuine computation error, never an
     * «absent optional variable».</p>
     */
    public ProcessVariable evaluateMapping(IoMappingExtensionModel.Mapping mapping, List<ProcessVariable> variables) {
        if (mapping.getSource() == null || mapping.getTarget() == null || mapping.getTarget().isBlank()) {
            return null;
        }
        String source = mapping.getSource();
        if (source.isBlank()) {
            // WO-ENG-29: degenerate but previously-valid shape — legacy JSR-223 eval of
            // "" returned null → "" STRING variable; keep byte-identical, no incident.
            Object legacy = scriptService.evaluateExpression(source, variables);
            return toProcessVariable(mapping.getTarget(), legacy);
        }
        String expression = source.startsWith("=") ? source.substring(1) : source;
        Map<String, Object> context = toFeelContext(variables, objectMapper);
        // WO-ENG-20: same budget entry point (pool/timeout/bulkhead) as every other
        // FeelEngineApi caller — resolveExpression and DmnServiceImpl.
        org.camunda.feel.api.EvaluationResult result = feelBudget.evaluateExpression(expression, context);
        // WO-ENG-29: failure-vs-null distinction. HARD failure (!isSuccess, e.g.
        // syntax errors — the Zeebe fix class) always throws FeelEvaluationException
        // (incident path). A NO_VARIABLE_FOUND suppressed failure ALSO throws —
        // EXCEPT when the result carries a non-null VALUE (e.g. a fallback after
        // `??`/`default()`, or a value produced before the nested error): then the
        // failure is recorded but the expression yielded data, so callers proceed
        // with the value as before (legacy JSR-223 returned the value, never the
        // failure). A clean success with null result and EMPTY suppressed list is
        // honest null (explicit =null literal, present-but-null variable) — legacy
        // value conversion as before, no exception.
        // Verified against feel-engine 1.19.3 native API — see report probe table:
        // missing bare name → success/null + suppressed NO_VARIABLE_FOUND;
        // =null literal → success/null + EMPTY suppressed; syntax error → !success.
        boolean hardFailure = result.isFailure();
        boolean missingVariable = !result.suppressedFailures().isEmpty() && result.result() == null;
        if (hardFailure || (missingVariable && strictMissing)) {
            throw new com.zorrodev.bpm.engine.service.FeelEvaluationException(
                "io-mapping source '" + source + "' failed to evaluate for target '"
                    + mapping.getTarget() + "': "
                    + (result.isFailure() ? result.failure() : result.suppressedFailures().mkString("; ")));
        }
        if (missingVariable) {
            // WO-ENG-30 lenient mode: legacy silent behaviour + WARN for log-based inventory.
            log.warn("io-mapping source '{}' references a missing variable for target '{}'"
                + " — legacy '' applied (zorrobpm.engine.io-mapping.strict-missing=false)",
                source, mapping.getTarget());
        }
        return toProcessVariable(mapping.getTarget(), result.result());
    }

    /**
     * WO-ENG-29: FEEL variable context with real Java types (LONG → Long, DOUBLE →
     * BigDecimal, BOOLEAN → Boolean, JSON → Map/List, else raw string) — the same
     * conversion the legacy JSR-223 path applied in
     * {@code ScriptServiceImpl.buildContext} and the DMN path applies in
     * {@code DmnServiceImpl.toVariableMap}. Shared here so io-mapping evaluation
     * through the native API sees the same values as every other FEEL caller.
     * A variable set explicitly to a null VALUE stays a present-but-null entry
     * (FEEL resolves the name to null — honest null, not «not found»).
     */
    public static Map<String, Object> toFeelContext(List<ProcessVariable> variables,
            tools.jackson.databind.ObjectMapper mapper) {
        Map<String, Object> map = new HashMap<>();
        if (variables == null) {
            return map;
        }
        for (ProcessVariable variable : variables) {
            if (variable.getType() == ProcessVariableType.LONG) {
                map.put(variable.getName(), Long.valueOf(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.BOOLEAN) {
                map.put(variable.getName(), Boolean.valueOf(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.DOUBLE) {
                map.put(variable.getName(), new java.math.BigDecimal(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.JSON) {
                map.put(variable.getName(), mapper.readValue(variable.getValue(), Object.class));
            } else {
                map.put(variable.getName(), variable.getValue());
            }
        }
        return map;
    }

    // ─── Type conversion ────────────────────────────────────────────────

    /**
     * WO-ENG-25 (NEW-05): script-FEEL (JSR-223) отдаёт числа как
     * {@code scala.math.BigDecimal} — точное значение, но чужой тип: без
     * нормализации оно не узнаётся {@code java.math.BigDecimal}-спецветками
     * ниже и уходит в double-детур с потерей точности. {@code .bigDecimal()} —
     * точная конвертация без округления (scala держит те же unscaledValue и
     * scale, что java). Прямая ссылка на scala-тип — принятый паттерн этого
     * файла (см. {@code isStructuredResult} ниже).
     */
    static Object normalizeFeelNumber(Object result) {
        if (result instanceof scala.math.BigDecimal sbd) {
            return sbd.bigDecimal();
        }
        return result;
    }

    public ProcessVariable toProcessVariable(String name, Object result) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        // WO-ENG-25 (NEW-05): нормализация ДО любых проверок — иначе
        // scala.BigDecimal ≥2⁵³ с дробью идёт в isIntegral через double,
        // ошибочно считается «целым», и longValueExact падает на дробном
        // значении с ложным "outside LONG range".
        Object normalized = normalizeFeelNumber(result);
        if (normalized instanceof Boolean b) {
            variable.setType(ProcessVariableType.BOOLEAN);
            variable.setValue(b.toString());
        } else if (normalized instanceof Number number && isIntegral(number)) {
            // WO-ENG-21 (N10): целое вне диапазона Long — явный EngineException,
            // а не молчаливое усечение longValue() (у BigDecimal теряются старшие
            // биты, у double — насыщение к Long.MAX_VALUE; оба тихо меняют
            // значение). Контракт: integral && withinLongRange → LONG точно.
            variable.setType(ProcessVariableType.LONG);
            variable.setValue(Long.toString(longValueExact(name, number)));
        } else if (normalized instanceof Number number) {
            java.math.BigDecimal bd = (number instanceof java.math.BigDecimal x)
                ? x : java.math.BigDecimal.valueOf(number.doubleValue());
            variable.setType(ProcessVariableType.DOUBLE);
            variable.setValue(bd.toPlainString());
        } else if (isStructuredResult(normalized)) {
            variable.setType(ProcessVariableType.JSON);
            variable.setValue(objectMapper.writeValueAsString(toJavaStructure(normalized)));
        } else if (normalized instanceof java.time.temporal.TemporalAccessor temporal) {
            // WO-ENG-33: FEEL date/time-объект, дошедший досюда, сериализуем в
            // interoperable ISO-строку с офсетом — НЕ голым toString(). У
            // ZonedDateTime toString() даёт Java-формат с именем зоны
            // (2026-09-30T15:49:48.966494894+05:00[Asia/Almaty]), который не
            // парсит никто кроме Java (живой инцидент: Go-API ответило
            // extra text: "[Asia/Almaty]"). Контракт типа НЕ меняется: всё
            // равно STRING, меняется только содержимое для date-объектов.
            //
            // Слой 1 из WO (string(now()) средствами FEEL, суффикс @ZoneId) —
            // НЕ чиним: string() исполняется ВНУТРИ feel-scala 1.19.3 (та же
            // линия, что Camunda 8), это апстрим-паритет, руками в либу не
            // лезем. Сюда такие значения приходят уже готовыми String и идут
            // общим else ниже нетронутыми — специально.
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(formatFeelDatetime(name, temporal));
        } else {
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(normalized == null ? "" : normalized.toString());
        }
        return variable;
    }

    /**
     * WO-ENG-33: ISO-сериализация FEEL date/time-объектов в STRING-переменные.
     * {@code ZonedDateTime}/{@code OffsetDateTime} → {@code ISO_OFFSET_DATE_TIME}
     * ({@code ...+05:00}, RFC 3339 — ест Go-layout
     * {@code 2006-01-02T15:04:05Z07:00}); {@code Instant} → {@code ISO_INSTANT};
     * zone-naive {@code LocalDateTime}/{@code LocalDate}/{@code LocalTime} →
     * {@code ISO_LOCAL_*} БЕЗ офсета (зона неизвестна — честно, не выдумываем;
     * интерпретация naive-значений в businessZone живёт в
     * {@code computeDueAt}, здесь только формат). Неизвестный
     * {@code TemporalAccessor} — fail-closed явным {@code EngineException}, а не
     * молчаливый {@code toString()} с квадратными скобками: FEEL date/time
     * отдают только типы выше (Period/Duration — вообще не TemporalAccessor),
     * так что это охранник от будущих типов, не от текущего FEEL-набора.
     */
    static String formatFeelDatetime(String name, java.time.temporal.TemporalAccessor temporal) {
        if (temporal instanceof java.time.ZonedDateTime zdt) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt);
        }
        if (temporal instanceof java.time.OffsetDateTime odt) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt);
        }
        if (temporal instanceof java.time.Instant instant) {
            return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
        }
        if (temporal instanceof java.time.LocalDateTime ldt) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ldt);
        }
        if (temporal instanceof java.time.LocalDate ld) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(ld);
        }
        if (temporal instanceof java.time.LocalTime lt) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_TIME.format(lt);
        }
        if (temporal instanceof java.time.OffsetTime ot) {
            // FEEL time() с зоной: toString() здесь и так ISO (15:00+05:00,
            // без скобок) — фиксируем формат явно, инцидента нет.
            return java.time.format.DateTimeFormatter.ISO_OFFSET_TIME.format(ot);
        }
        throw new com.zorrodev.bpm.contract.exception.EngineException(
            "Variable '" + name + "' holds an unsupported date/time type "
                + temporal.getClass().getName()
                + " — refusing to store a non-interoperable toString() value");
    }

    /**
     * WO-ENG-21 (N10): точное целое в диапазоне Long. Целое ВНЕ диапазона
     * (например 2^63 из FEEL/DMN-результата) — EngineException с именем
     * переменной и границами, а не усечённое значение. Тот же тип исключения,
     * что у соседних числовых domain-ошибок (cardinality F23 в
     * {@code MultiInstanceExecutor}, priority WO-C8-30) — и та же маршрутизация
     * в вызывающих путях; откат в binary double для денег запрещён самим WO.
     */
    private long longValueExact(String name, Number number) {
        try {
            if (number instanceof java.math.BigDecimal bd) {
                return bd.longValueExact();
            }
            if (number instanceof java.math.BigInteger bi) {
                return bi.longValueExact();
            }
            if (number instanceof Long l) {
                return l;
            }
            if (number instanceof Integer i) {
                return i;
            }
            if (number instanceof Short s) {
                return s;
            }
            if (number instanceof Byte b) {
                return b;
            }
            // Double/Float/прочие: через точное десятичное представление —
            // longValueExact() отказывает и на выходе за диапазон, и на дроби
            // (дроби сюда не доходят — только isIntegral(), но guard полный).
            // Сюда попадают и целозначные double вне диапазона (насыщение
            // longValue() вместо отказа — та же тихая порча, что у BigDecimal).
            return new java.math.BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException e) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "Variable '" + name + "' value " + number + " is an integer outside LONG range"
                    + " [" + Long.MIN_VALUE + ", " + Long.MAX_VALUE + "]"
                    + " — refusing to store a truncated value", e);
        }
    }

    public boolean isIntegral(Number number) {
        // WO-ENG-25 (NEW-05): любой BigDecimal-подобный — через scale, не
        // через double. Нормализация здесь же, чтобы прямые вызывающие (не
        // только toProcessVariable) получали тот же контракт: scala.BigDecimal
        // ≥2⁵³ с дробью через double теряет дробную часть (rint == d) и
        // ложно считается целым.
        Number normalized = number instanceof scala.math.BigDecimal sbd ? sbd.bigDecimal() : number;
        if (normalized instanceof Long || normalized instanceof Integer || normalized instanceof Short || normalized instanceof Byte) {
            return true;
        }
        if (normalized instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        double d = normalized.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    public boolean isStructuredResult(Object v) {
        return v instanceof java.util.Map || v instanceof java.util.List
            || v instanceof scala.collection.Map || v instanceof scala.collection.Iterable;
    }

    /**
     * WO-C8-32: appends {@code value} to the root JSON-list variable {@code name}
     * (creating it when absent). Generalised from the multi-instance output-collection
     * append (moved here verbatim so MI and ad-hoc share one mechanism instead of two).
     * <p>
     * WO-REL-41 (B-8, п.1): the read-modify-write now happens in ONE SQL statement
     * ({@code DBService.appendJsonElement}) — the old root-scoped read, Java-side
     * merge and root-scoped write lost elements when parallel MI branches (or
     * ad-hoc flows) completed concurrently.
     */
    public void appendToJsonList(UUID processInstanceId, String name, Object value) {
        dbService.appendJsonElement(processInstanceId, name,
            objectMapper.writeValueAsString(toJavaStructure(value)));
    }

    // ─── Boundary helpers ────────────────────────────────────────────

    public Instant computeDueAt(BpmnElementModel element) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId());
    }

    public Instant computeDueAt(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer, String elementId) {
        return computeDueAtFallback(timer, elementId);
    }

    /**
     * Computes the due-at instant for a timer, resolving FEEL expressions ({@code =expr}) against
     * process instance variables before parsing.  Literal ISO-8601 values (no leading {@code =}) use
     * the original parsing path.
     *
     * @param element          the BPMN element with a timer extension
     * @param processInstanceId the process instance whose variables are available to FEEL
     * @return the computed {@link Instant} when the timer should fire
     * @throws com.zorrodev.bpm.contract.exception.EngineException if the expression is null,
     *         the FEEL evaluation returns null, or the result cannot be converted to the expected type
     */
    public Instant computeDueAt(BpmnElementModel element, UUID processInstanceId) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId(), processInstanceId);
    }

    /**
     * Computes the due-at instant for a timer, resolving FEEL expressions ({@code =expr}) against
     * process instance variables before parsing.
     */
    public Instant computeDueAt(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer,
                                String elementId, UUID processInstanceId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Timer event " + elementId + " has no timer definition");
        }
        String expression = timer.getExpression();
        if (expression.startsWith("=")) {
            String feelExpr = expression.substring(1);
            Object value = scriptService.evaluateExpression(feelExpr, dbService.getVariables(processInstanceId));
            if (value == null) {
                throw new com.zorrodev.bpm.contract.exception.EngineException(
                    "Timer event " + elementId + ": FEEL expression '" + expression + "' returned null");
            }
            return switch (timer.getType()) {
                case DURATION -> {
                    if (value instanceof Duration d) {
                        yield Instant.now().plus(d);
                    }
                    try {
                        yield Instant.now().plus(Duration.parse(value.toString()));
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid duration (got: " + value + ")", e);
                    }
                }
                case DATE -> {
                    if (value instanceof Instant i) {
                        yield i;
                    }
                    if (value instanceof java.time.OffsetDateTime odt) {
                        // WO-ENG-4: explicit offset → use it
                        yield odt.toInstant();
                    }
                    if (value instanceof java.time.ZonedDateTime zdt) {
                        // WO-ENG-4: explicit zone → use it
                        yield zdt.toInstant();
                    }
                    if (value instanceof java.time.LocalDateTime ldt) {
                        // WO-ENG-4: zone-naive → interpret in businessZone, not UTC
                        yield ldt.atZone(businessZone).toInstant();
                    }
                    if (value instanceof java.time.LocalDate ld) {
                        // WO-ENG-4: zone-naive → interpret in businessZone, not UTC
                        yield ld.atStartOfDay(businessZone).toInstant();
                    }
                    if (value instanceof java.util.Date d) {
                        yield d.toInstant();
                    }
                    try {
                        yield Instant.parse(value.toString());
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid date/instant (got: " + value + ")", e);
                    }
                }
                case CYCLE -> {
                    try {
                        // WO-ENG-4: use businessZone for cycle/cron zone resolution
                        yield com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(value.toString(), Instant.now(), businessZone);
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid cycle expression (got: " + value + ")", e);
                    }
                }
            };
        }
        // Non-FEEL expression: use the original literal parsing path
        return computeDueAtFallback(timer, elementId);
    }

    /** Original literal-only parsing path, kept for backward compatibility and as the non-FEEL fallback. */
    private Instant computeDueAtFallback(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer, String elementId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Timer event " + elementId + " has no timer definition");
        }
        return switch (timer.getType()) {
            case DURATION -> Instant.now().plus(Duration.parse(timer.getExpression()));
            case DATE -> Instant.parse(timer.getExpression());
            case CYCLE -> com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(timer.getExpression(), Instant.now(), businessZone);
        };
    }

    public String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getCorrelationKeyExpression)
            .filter(s -> !s.isBlank())
            .orElse(null);
        if (expression == null) {
            return null;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object value = scriptService.evaluateExpression(expression, dbService.getVariables(processInstanceId));
        return value == null ? null : value.toString();
    }

    public Object toJavaStructure(Object v) {
        if (v instanceof scala.collection.Map<?, ?> sm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            scala.collection.Iterator<?> it = sm.iterator();
            while (it.hasNext()) {
                scala.Tuple2<?, ?> entry = (scala.Tuple2<?, ?>) it.next();
                out.put(String.valueOf(entry._1()), toJavaStructure(entry._2()));
            }
            return out;
        }
        if (v instanceof scala.collection.Iterable<?> si) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            scala.collection.Iterator<?> it = si.iterator();
            while (it.hasNext()) {
                out.add(toJavaStructure(it.next()));
            }
            return out;
        }
        if (v instanceof java.util.Map<?, ?> jm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            jm.forEach((k, val) -> out.put(String.valueOf(k), toJavaStructure(val)));
            return out;
        }
        if (v instanceof java.util.List<?> jl) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object e : jl) {
                out.add(toJavaStructure(e));
            }
            return out;
        }
        return v;
    }

    // ── WO-C8-34 (CR-06): compensation thrower bookkeeping ────────────────────
    //
    // These used to exist TWICE — once in CompensationThrowHandler (throw side) and
    // once in CompletionService (resume side) — and the copies had already DIVERGED:
    // the throw side snapshotted its candidate list, the resume side recomputed it, so
    // a target that completed AFTER the throw (handler never launched) kept the parked
    // thrower waiting forever (red-team B1). One implementation, both callers.

    /**
     * Candidates one compensation thrower owns: scope-confined completed activities
     * (the CancelEndHandler pattern) plus the pre-existing {@code activityRef} filter.
     * {@code tokenId} is the thrower's own token — its {@code scopeActivityId} decides
     * the scope.
     */
    public List<Activity> compensationTargets(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel::getReference)
            .orElse(null);
        Token throwToken = dbService.findToken(tokenId).orElse(null);
        UUID scopeActivityId = throwToken == null ? null : throwToken.getScopeActivityId();
        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (scopeActivityId != null) {
            targets = filterActivitiesInScope(processInstanceId, targets, scopeActivityId);
        }
        if (activityRef != null) {
            String ref = activityRef;
            targets = targets.stream().filter(a -> ref.equals(a.getBpmnElementId())).toList();
        }
        return targets;
    }

    public boolean compensationThrowerHasPending(UUID processInstanceId, UUID throwActivityId,
            BpmnProcessDefinitionModel bpmn, List<Activity> targets) {
        return compensationThrowerHasPending(processInstanceId, throwActivityId, bpmn, targets, java.util.Set.of());
    }

    /**
     * WO-C8-34 (CR-06, red-team B1): does {@code throwActivityId} still wait for a
     * compensation handler?
     *
     * <p>The throw side snapshotted its candidate list; a resume that recomputes the list
     * sees MORE completed activities than the throw did (anything finished afterwards),
     * and such a target's handler was never launched — so "no row for the handler" read
     * as "pending" and the thrower parked forever. The thrower's own row creation time IS
     * the snapshot: a target belongs to this throw iff it completed at or before the
     * throw. The tie (same millisecond) fails closed — the row is kept, we still wait.
     *
     * @param forceTerminalHandlerIds handler elements the CALLER has just declared
     *        finished-with-failure (the retry-exhausted path). Their row may still read
     *        CREATED/IN_PROGRESS inside the same transaction — {@code errorActivity} is a
     *        bulk UPDATE that the repeatable-read snapshot of the following query does not
     *        see (the same hole WO-ENG-23 hit in {@code IncidentService}). The failing
     *        path is the authority on its own outcome, so it passes the verdict in rather
     *        than hoping the snapshot agrees.
     */
    public boolean compensationThrowerHasPending(UUID processInstanceId, UUID throwActivityId,
            BpmnProcessDefinitionModel bpmn, List<Activity> targets,
            java.util.Set<String> forceTerminalHandlerIds) {
        Activity throwerRow = throwActivityId == null ? null : dbService.getActivity(throwActivityId);
        Instant thrownAt = throwerRow == null ? null : throwerRow.getCreatedAt();
        List<Activity> owned = targets;
        if (thrownAt != null) {
            final Instant cut = thrownAt;
            owned = targets.stream()
                .filter(a -> a.getCompletedAt() == null || !a.getCompletedAt().isAfter(cut))
                .toList();
        }
        Map<String, BpmnElementModel> boundaryIndex = compensationBoundaryIndex(bpmn);
        for (Activity target : owned) {
            String handlerId = compensationHandlerId(boundaryIndex, bpmn, target.getBpmnElementId());
            if (handlerId == null || forceTerminalHandlerIds.contains(handlerId)) {
                continue;
            }
            if (isCompensationHandlerPending(processInstanceId,
                    throwerRow == null ? null : throwerRow.getToken(), handlerId)) {
                return true;
            }
        }
        return false;
    }

    /** host-id → its compensation boundary event (one pass over the model). */
    public Map<String, BpmnElementModel> compensationBoundaryIndex(BpmnProcessDefinitionModel bpmn) {
        Map<String, BpmnElementModel> index = new HashMap<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (attached != null) {
                index.putIfAbsent(attached, element);
            }
        }
        return index;
    }

    /** handler element id for a compensated host, or null when it has none. */
    public String compensationHandlerId(Map<String, BpmnElementModel> boundaryIndex,
            BpmnProcessDefinitionModel bpmn, String hostElementId) {
        BpmnElementModel boundary = boundaryIndex.get(hostElementId);
        if (boundary == null) {
            return null;
        }
        String handlerId = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel::getCompensationHandlerId)
            .orElse(null);
        return handlerId == null || bpmn.getElement(handlerId) == null ? null : handlerId;
    }

    /**
     * Latest-row-wins for one compensation handler element. Pending unless a COMPLETED
     * row exists AND no row is newer than it.
     *
     * <p>WO-C8-34 red-team B2: a handler whose retries ran out is ERROR + incident. That
     * IS the outcome the operator sees, so the waiting thrower must not hang on it (nor on
     * a handler cancelled by an unrelated interrupting boundary). Those rows are in
     * neither instance-wide list ({@code getActiveActivities} = CREATED/IN_PROGRESS,
     * {@code getCompletedActivities} = COMPLETED), so they are read with one token-scoped
     * query — handlers run on the thrower's own token ({@code runCompensation} passes it
     * to {@code executor.execute}). A null token finds no terminal rows and fails closed.
     */
    public boolean isCompensationHandlerPending(UUID processInstanceId, UUID tokenId, String handlerId) {
        Instant newestCompleted = null;
        UUID newestCompletedId = null;
        Instant newestActive = null;
        UUID newestActiveId = null;
        Instant newestFailed = null;
        UUID newestFailedId = null;
        for (Activity a : dbService.getCompletedActivities(processInstanceId)) {
            if (handlerId.equals(a.getBpmnElementId())
                && (newestCompleted == null || compareActivityRecency(a.getCompletedAt(), a.getId(),
                    newestCompleted, newestCompletedId) > 0)) {
                newestCompleted = a.getCompletedAt();
                newestCompletedId = a.getId();
            }
        }
        if (tokenId != null) {
            for (Activity a : dbService.getActivitiesByTokenAndBpmnElementId(tokenId, handlerId)) {
                if (a.getStatus() != ActivityStatus.ERROR && a.getStatus() != ActivityStatus.CANCELLED) {
                    continue;
                }
                if (newestFailed == null || compareActivityRecency(a.getCompletedAt(), a.getId(),
                    newestFailed, newestFailedId) > 0) {
                    newestFailed = a.getCompletedAt();
                    newestFailedId = a.getId();
                }
            }
        }
        for (Activity a : dbService.getActiveActivities(processInstanceId)) {
            if (handlerId.equals(a.getBpmnElementId())
                && (newestActive == null || compareActivityRecency(a.getCreatedAt(), a.getId(),
                    newestActive, newestActiveId) > 0)) {
                newestActive = a.getCreatedAt();
                newestActiveId = a.getId();
            }
        }
        if (newestFailed != null
            && (newestCompleted == null || compareActivityRecency(newestFailed, newestFailedId,
                newestCompleted, newestCompletedId) > 0)
            && (newestActive == null || compareActivityRecency(newestFailed, newestFailedId,
                newestActive, newestActiveId) > 0)) {
            return false; // newest word on this handler is "failed/cancelled" — waiting is over
        }
        if (newestCompleted == null) {
            return true; // launched (boundary maps it) but never finished — still pending
        }
        if (newestActive == null) {
            return false;
        }
        return compareActivityRecency(newestActive, newestActiveId, newestCompleted, newestCompletedId) > 0;
    }

    /** Timestamp-then-id ordering; a null timestamp sorts as the older one. */
    public static int compareActivityRecency(Instant t1, UUID id1, Instant t2, UUID id2) {
        if (t1 != null && t2 != null) {
            int c = t1.compareTo(t2);
            if (c != 0) {
                return c;
            }
        } else if (t1 != null) {
            return 1;
        } else if (t2 != null) {
            return -1;
        }
        if (id1 != null && id2 != null) {
            return id1.compareTo(id2);
        }
        return 0;
    }

    // ── WO-C8-35 (CR-09) раунд 3: единое правило готовности inclusive-join ──────────
    //
    // Два join-механизма жили по разным правилам: parallel join сверяет приходы со своими
    // СОБСТВЕННЫми входящими, inclusive join — со статическим счётчиком `expected`, который
    // писал только inclusive-сплит. От XOR/параллельного форка/неявной AND-вилки/выхода из
    // подпроцесса счётчика нет → join логировал «не готов» на КАЖДЫЙ приход и ждал вечно,
    // молча (без инцидента).
    //
    // Раунд 3 (HOLD красного флага, MAJOR-1/MAJOR-2) счётчик снят ЦЕЛИКОМ: он писался
    // вслепую (UK-нарушение на двух сплитах в один join) и уезжал на шлюз, достигаемый
    // 2 из 3 ветвей. Теперь правило одно для ВСЕХ inclusive-join и целиком выводится из
    // состояния инстанса:
    //
//     ready(J) ⟺ никто в инстансе НЕ МОЖЕТ доставить в J ещё одну ветвь
    //
    // «кто-то может» — объединение ДВУХ множеств (раунд 5: было три, см. ниже):
    //   (a) активные activity                   — обычная живая ветвь, и вместе с ней
    //                                               ВСЕ граничные события, привязанные к её
    //                                               элементу (ребро границы внутри canReach);
    //   (b) припаркованные шлюзы (ШАГ 1/3)      — ветвь, дошедшая до join'а и ждущая, тоже
    //                                               доставка в полёте; плюс сам сплит в фазе
    //                                               разветвления (его activity остаётся живой
    //                                               весь фанаут — см. InclusiveGatewayHandler).
    //
    // Раунд 5: множество «взведённых триггеров» осталось, но перестало быть САМОСТОЯТЕЛЬНЫМ
    // правилом. Раньше оно и было вселенной — и потому не видело CONDITIONAL/ERROR/ESCALATION-границ
    // (строки armed у них нет никогда): join срабатывал на первом приходе, а по границе ВТОРЫМ
    // (BLOCKER-3 red-team, живой прогон). Теперь structural-ребро границы проходится для ЛЮБОГО типа,
    // а armed-набор используется ровно там, где он единственный источник знания: «эта граница уже
    // сработала, второй выстрел невозможен» (timer/message/signal). На определениях без таких
    // границ запросов не делается вовсе.
    //
    // Намеренная асимметрия: консервативность (b) может заставить join ЖДАТ лишнего, но
    // никогда не срабатывает раньше времени. Обратную сторону — «последний доставщик умер» —
    // снимает перепроверка припаркованных join'ов при каждой деактивации (ШАГ 3, B2), иначе
    // консервативность была бы вечным ожиданием (BLOCKER-2).

    /**
     * Can a live execution sitting on {@code fromElementId} still reach
     * {@code targetElementId}? Forward BFS, {@code visited}-guarded, so it terminates on cyclic
     * graphs.
     *
     * <p>Reachability goes through TWO edges out of every visited element (WO-C8-35 раунд 5,
     * BLOCKER-3 red-team):
     * <ol>
     *   <li>исходящие SEQUENCE-потоки элемента;</li>
     *   <li>исходящие потоки ГРАНИЧНЫХ событий, привязанных к нему
     *       ({@code BpmnProcessDefinitionModel.getBoundaryEventsAttachedTo}) — любого типа,
     *       прерывающих и нет.</li>
     * </ol>
     * Второе ребро обязано быть здесь, а не в отдельном «взведённом» множестве: граница привязана
     * к хосту через {@code attachedToRef}, а не потоком, и наличие строки armed есть только у
     * timer/message/signal — у CONDITIONAL/ERROR/ESCALATION границы её нет ВООБЩЕ. Пока хост жив,
     * граница может выстрелить, значит хост — возможный доставщик ветви; хост умер → хост исчез из
     * множества живых activity, и правило перечитывается на деактивации (перепроверка
     * припаркованных join'ов).
     *
     * <p>Conditions on flows out of a gateway are NOT evaluated: a path through a conditional
     * gateway counts as reachable even when its condition may evaluate false. That is
     * deliberately conservative — the join waits rather than fires early (the opposite failure
     * duplicates business side effects, which is what the static counter used to cause).
     * The cost of that conservatism is a possible over-wait, and it is paid back by
     * {@code InclusiveGatewayHandler.resumeParkedInclusiveJoins}: when the last possible
     * deliverer dies (XOR took its other branch, cancellation, termination, a boundary fired,
     * an error/escalation boundary killed its host), every parked inclusive join is re-evaluated.
     * Counting a false-condition path as reachable IS the wait-forever defect if nothing ever
     * re-checks — that was BLOCKER-2, and this Javadoc previously asserted the exact opposite
     * of what the code did.
     */
    public boolean canReach(BpmnProcessDefinitionModel bpmn, String fromElementId, String targetElementId) {
        return canReachWithLiveBoundaries(bpmn, fromElementId, targetElementId, null);
    }

    /**
     * То же обход, но ребро границы проходится только для границ, которые ещё МОГУТ выстрелить.
     *
     * <p><b>Раунд 6 (BLOCKER-6): критерий ИНВЕРТИРОВАН — «исключать отработавшие», а не «включать
     * взведённые» (решение CTO).</b> Прежний гейт по {@code armedBoundaryElementIds} отделял
     * «граница снята» от «граница ещё не взведена», а эти состояния в нём неразличимы: у границы
     * на хосте, который ЕЩЁ НЕ СТАРТОВАЛ, строки job'а нет вовсе, и ребро молча пропадало — join
     * срабатывал раньше времени, а по границе срабатывал ВТОРЫМ (живой прогон red-team, диаграмма
     * «таймаут-ветка + основная ветка в общий join»). Теперь отсеиваются только границы из
     * {@code exhaustedBoundaryElementIds} — те, доставить которым уже нечем.
     *
     * <p>Что значит «отработала» (см. {@code DBService.getExhaustedBoundaryElementIds}): хост умер
     * (все его activity-строки терминальные — тогда снимается ЛЮБАЯ граница, хоть у неё и нет
     * персистентной записи) либо одноразовый граничный таймер выстрелил и не перевзведён. У
     * message/signal/conditional/error/escalation-границ, которые могут срабатывать повторно, пока
     * хост жив, второго состояния нет вовсе — их снимает только смерть хоста, а до неё они считаются
     * достижимыми (консервативно: join подождёт, потом перепроверка по хуку).
     *
     * @param exhaustedBoundaryElementIds element id границ, которые доставить ветвь уже не могут;
     *                                   {@code null}/пусто — «все границы этого определения ещё
     *                                   могут выстрелить» (частый случай: запросов не было вовсе)
     */
    public boolean canReachWithLiveBoundaries(BpmnProcessDefinitionModel bpmn, String fromElementId,
            String targetElementId, java.util.Set<String> exhaustedBoundaryElementIds) {
        if (fromElementId == null || targetElementId == null || fromElementId.equals(targetElementId)) {
            return fromElementId != null && fromElementId.equals(targetElementId);
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromElementId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current.equals(targetElementId)) {
                return true;
            }
            BpmnElementModel element = bpmn.getElement(current);
            if (element == null) {
                continue;
            }
            enqueueOutgoingTargets(bpmn, element.getOutgoing(), queue);
            // Ребро границы: у хоста может быть граница, у которой НЕТ строки activity, но есть
            // исходящий поток в сторону join'а. Хост, который ещё не стартовал, в это множество
            // попадает по графу (taskSvc → taskX) — и обязан попасть, иначе join сработает до
            // срока (BLOCKER-6).
            for (BpmnElementModel boundary : bpmn.getBoundaryEventsAttachedTo(current)) {
                if (exhaustedBoundaryElementIds != null
                        && exhaustedBoundaryElementIds.contains(boundary.getId())) {
                    // хост умер либо одноразовый таймер отстрелял: ветвь эта уже доставлена
                    // (или будет доставлена отменой хоста), повторного выстрела не будет.
                    continue;
                }
                enqueueOutgoingTargets(bpmn, boundary.getOutgoing(), queue);
            }
        }
        return false;
    }

    private void enqueueOutgoingTargets(BpmnProcessDefinitionModel bpmn, List<String> outgoingFlowIds,
            Deque<String> queue) {
        if (outgoingFlowIds == null) {
            return;
        }
        for (String flowId : outgoingFlowIds) {
            BpmnFlowModel flow = bpmn.getFlow(flowId);
            if (flow != null && flow.getTargetRef() != null) {
                queue.add(flow.getTargetRef());
            }
        }
    }

    /**
     * Is some OTHER still-live execution in this instance able to reach the join?
     *
     * <p>Arrived branches are already COMPLETED by the time the join runs
     * ({@code FlowNavigator.processFlow} completes the flow row), so they do not count as live —
     * the arriving branch excludes itself without special-casing. An inclusive SPLIT that is
     * currently dispatching (its activity row stays live for the whole fan-out, see
     * {@code InclusiveGatewayHandler}) IS counted here: that is what keeps the join from firing
     * on the first arrival of a pass-through fan-out, whose sibling branches have no activity
     * row yet.
     */
    public boolean hasOtherLiveExecutionReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        return hasOtherLiveExecutionReaching(processInstanceId, bpmn, join,
            exhaustedBoundaryIds(processInstanceId, bpmn));
    }

    /**
     * Раунд 6 (BLOCKER-6): {@code exhaustedBoundaryElementIds} — element id границ инстанса,
     * доставить которые уже НЕЧЕГО (хост умер либо одноразовый таймер отстрелял). Если в
     * определении нет НИ ОДНОЙ границы, запросов не делается вовсе (обычный случай).
     */
    public boolean hasOtherLiveExecutionReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join, java.util.Set<String> exhaustedBoundaryElementIds) {
        for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
            String elementId = activity.getBpmnElementId();
            if (elementId == null || elementId.equals(join.getId())) {
                continue;
            }
            if (canReachWithLiveBoundaries(bpmn, elementId, join.getId(), exhaustedBoundaryElementIds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Раунд 6: границы, доставить которые уже нечем. Лениво и только если в определении есть
     * границы вообще (мемо в модели) — у большинства определений правило не делает ни одного
     * запроса.
     */
    private java.util.Set<String> exhaustedBoundaryIds(UUID processInstanceId, BpmnProcessDefinitionModel bpmn) {
        if (!bpmn.hasBoundaryEvent()) {
            return java.util.Set.of();
        }
        return dbService.getExhaustedBoundaryElementIds(processInstanceId,
            bpmn.getBoundaryHostByBoundaryElementId());
    }

    /**
     * WO-C8-35 (CR-09, ШАГ 1/3): a branch that already arrived at ANOTHER gateway and is parked
     * there is a delivery in flight too — it holds open arrival rows, and once that gateway
     * fires it continues downstream, possibly into this join. Without this the join could fire
     * while an upstream join was still holding a branch destined for it (MAJOR-1's partial-merge
     * shape: {@code j1} parks with A/B while {@code j2} waits on j1's outgoing).
     */
    public boolean hasParkedJoinReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        for (String gatewayId : dbService.getGatewaysWithOpenArrivals(processInstanceId)) {
            if (gatewayId.equals(join.getId())) {
                continue;
            }
            if (canReach(bpmn, gatewayId, join.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * WO-C8-35 (CR-09) раунд 3: THE single readiness rule for every inclusive join, whatever
     * split it comes from. See the block comment above for the two sets that make up
     * "can still deliver".
     *
     * <p>Раунд 5 (BLOCKER-3): множество «взведённых триггеров» УДАЛЕНО целиком вместе с тремя
     * запросами и индексом под них. Причина не в том, что оно было лишним, а в том, что оно было
     * НЕПОЛНЫМ: строка armed есть только у timer/message/signal, а условная и escalation-границы
     * её не имеют никогда, поэтому join, в который могла доставить такая граница, считал
     * инстанс пустым и срабатывал на первом приходе — а по границе срабатывал ВТОРЫМ
     * (живой прогон red-team BLOCKER-3). Теперь то же свойство получено СТРУКТУРНО, из
     * {@link #canReach}: живой хост + его границы = возможный доставщик, без единого запроса.
     */
    public boolean isInclusiveJoinReady(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        return !hasOtherLiveExecutionReaching(processInstanceId, bpmn, join,
                exhaustedBoundaryIds(processInstanceId, bpmn))
            && !hasParkedJoinReaching(processInstanceId, bpmn, join);
    }

}
