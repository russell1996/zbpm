package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
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

    public ElementSupport(DBService dbService, ScriptService scriptService,
            FeelBudget feelBudget, tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${zorrobpm.business-timezone:Asia/Almaty}") ZoneId businessZone) {
        this.dbService = dbService;
        this.scriptService = scriptService;
        this.feelBudget = feelBudget;
        this.objectMapper = objectMapper;
        this.businessZone = businessZone;
    }

    /**
     * Locks the activity's process instance and returns the activity in ONE
     * {@code SELECT ... FOR UPDATE} ({@code DBService.getActivityForUpdate}).
     * Serialises all execution touching one instance so concurrent async
     * branches cannot race on joins or double-advance a token; the row read
     * under the lock is consistent with it (a competing transaction has already
     * committed by the time we hold it).
     * <p>WO-REL-30 (B-3): single statement, no read/lock race window
     * (was: get + lock + get = 3 statements).
     * <p>Shared by {@link CompletionService}, {@link EventTrigger} and
     * {@link IncidentService} (WO-AUD-24 / P-24 dedup).
     */
    public Activity lockAndReload(UUID activityId) {
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
            return Integer.parseInt(resolved.trim());
        } catch (NumberFormatException e) {
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
                value = Integer.parseInt(resolved.trim());
            } catch (NumberFormatException e) {
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
     * Evaluates one io-mapping {@code source} (FEEL, optional leading '=') against the given
     * variables and converts the result into a {@link ProcessVariable} named {@code target}.
     * Returns {@code null} for a malformed mapping (missing source/target) — callers skip it.
     * <p>Shared by {@link #applyIoMappings} (activity-scoped writes) and WO-ENG-11 call-activity
     * mappings: input mappings seed a not-yet-created child instance, output mappings override the
     * propagateAllChildVariables toggle — same evaluation pattern, different write scope.</p>
     */
    public ProcessVariable evaluateMapping(IoMappingExtensionModel.Mapping mapping, List<ProcessVariable> variables) {
        if (mapping.getSource() == null || mapping.getTarget() == null || mapping.getTarget().isBlank()) {
            return null;
        }
        String expression = mapping.getSource().startsWith("=") ? mapping.getSource().substring(1) : mapping.getSource();
        Object value = scriptService.evaluateExpression(expression, variables);
        return toProcessVariable(mapping.getTarget(), value);
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
        } else {
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(normalized == null ? "" : normalized.toString());
        }
        return variable;
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
}
