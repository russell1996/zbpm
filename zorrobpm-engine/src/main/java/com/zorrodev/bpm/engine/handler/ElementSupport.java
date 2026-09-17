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
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
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
    private final FeelEngineApi feelEngineApi;
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
            FeelEngineApi feelEngineApi, tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${zorrobpm.business-timezone:Asia/Almaty}") ZoneId businessZone) {
        this.dbService = dbService;
        this.scriptService = scriptService;
        this.feelEngineApi = feelEngineApi;
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
            EvaluationResult result = feelEngineApi.evaluateExpression(raw.substring(1), vars);
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
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId, activityId);
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

    public ProcessVariable toProcessVariable(String name, Object result) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        if (result instanceof Boolean b) {
            variable.setType(ProcessVariableType.BOOLEAN);
            variable.setValue(b.toString());
        } else if (result instanceof Number number && isIntegral(number)) {
            variable.setType(ProcessVariableType.LONG);
            variable.setValue(Long.toString(number.longValue()));
        } else if (result instanceof Number number) {
            java.math.BigDecimal bd = (number instanceof java.math.BigDecimal x)
                ? x : java.math.BigDecimal.valueOf(number.doubleValue());
            variable.setType(ProcessVariableType.DOUBLE);
            variable.setValue(bd.toPlainString());
        } else if (isStructuredResult(result)) {
            variable.setType(ProcessVariableType.JSON);
            variable.setValue(objectMapper.writeValueAsString(toJavaStructure(result)));
        } else {
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(result == null ? "" : result.toString());
        }
        return variable;
    }

    public boolean isIntegral(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return true;
        }
        if (number instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        double d = number.doubleValue();
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
