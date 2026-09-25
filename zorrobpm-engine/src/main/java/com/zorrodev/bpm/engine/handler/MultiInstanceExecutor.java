package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-instance collaborator extracted from ActivityServiceImpl (WO-AUD-16).
 * Contains all multi-instance logic: entering, spawning, continuing, and aggregating.
 * Navigation via FlowNavigator; dispatch via TokenExecutor (passed as parameter, not injected).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiInstanceExecutor {

    private final DBService dbService;
    private final ScriptService scriptService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmnService bpmnService;
    private final ElementSupport elementSupport;
    private final BoundaryScheduler boundaryScheduler;

    private final FlowNavigator flowNavigator;

    /**
     * WO-REL-31 F23: upper bound on how many MI instances a single multi-instance entry may
     * spawn synchronously in one transaction. Justification: {@code MAX_MI_CARDINALITY = 1_000}
     * is 2× the CR-3 fan-out batch size (500) and already a heavy single-transaction operation
     * (~1 000 activities + ~1 000 user/service-task rows + ~2 000 variable rows + boundary
     * subscriptions ≈ 5-6k rows). Anything above is unbounded synchronous work in one
     * transaction — a process model asking for it should use a different pattern (nested
     * multi-instance, message-driven chunks), not a giant spawn. The audit flagged the same
     * OOM risk on the 10k-subscriber fan-out; this is the intra-instance twin of that bound.
     */
    static final int MAX_MI_CARDINALITY = 1_000;

    /**
     * WO-PERF-9 (B-8, full-scan): identifiers potentially referenced by a
     * FEEL expression — a conservative SUPERSET of every name the Camunda
     * FEEL engine could resolve from the variable bindings. Fetching exactly
     * these rows evaluates byte-identically to the full-scope read: bindings
     * are a map keyed by exact name and FEEL names are case-sensitive, so any
     * name the engine can look up appears verbatim as an identifier token in
     * the source. Unreferenceable spellings (string literals are stripped;
     * comment/keyword tokens that remain) only add harmless extra fetches —
     * they never remove a needed row. Dotted paths ({@code a.b}) contribute
     * both parts (the root {@code a} is what the engine resolves). Unicode
     * letters are included — FEEL identifiers are not ASCII-only.
     */
    static Set<String> extractFeelVariableNames(String expression) {
        Set<String> names = new LinkedHashSet<>();
        if (expression == null || expression.isBlank()) {
            return names;
        }
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                flushFeelToken(cur, names);
                inString = true;
                continue;
            }
            if (c == '_' || Character.isLetter(c)) {
                cur.append(c);
                continue;
            }
            if (cur.length() > 0 && Character.isDigit(c)) {
                cur.append(c);
                continue;
            }
            flushFeelToken(cur, names);
        }
        flushFeelToken(cur, names);
        return names;
    }

    private static void flushFeelToken(StringBuilder cur, Set<String> names) {
        if (cur.length() > 0) {
            names.add(cur.toString());
            cur.setLength(0);
        }
    }

    /**
     * WO-PERF-9: root variables needed to evaluate the given FEEL
     * expressions — one indexed SELECT for exactly the referenced names
     * instead of {@code getVariables(pi)} over the whole instance scope.
     */
    private List<ProcessVariable> rootVariablesFor(UUID processInstanceId, String... expressions) {
        Set<String> names = new LinkedHashSet<>();
        for (String e : expressions) {
            names.addAll(extractFeelVariableNames(e));
        }
        return dbService.getVariablesByNames(processInstanceId, names);
    }

    /**
     * WO-PERF-9: scoped counterpart of {@link #rootVariablesFor} — the
     * root+scope merged view restricted to the referenced names ("scoped
     * wins" preserved by the query layer).
     */
    private List<ProcessVariable> scopedVariablesFor(UUID processInstanceId, UUID scopeId, String... expressions) {
        Set<String> names = new LinkedHashSet<>();
        for (String e : expressions) {
            names.addAll(extractFeelVariableNames(e));
        }
        return dbService.getScopedVariablesByNames(processInstanceId, scopeId, names);
    }

    public boolean isMultiInstance(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .isPresent();
    }

    /**
     * Multi-instance user task. Parallel: spawns all N user-task activities on the same token at once.
     * Sequential: spawns only the first instance; the next is created as each completes (see
     * {@link #multiInstanceContinue}). N comes from {@code loopCardinality}; the join is told to expect N
     * (reusing the parallel/inclusive arrival mechanism).
     */
    public void enter(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, TokenExecutor executor) {
        MultiInstanceExtensionModel mi = bpmnElement.getExtensions().getMultiInstanceExtension();
        int count;
        Object collection;
        try {
            // WO-REL-41 (B-8, п.3): ONE root read and ONE inputCollection FEEL
            // eval per entry — shared by the cardinality resolution below and
            // the spawn that follows, instead of each fetching/evaluating them
            // again on the same transition.
            // WO-PERF-9 (B-8, full-scan): the read is pinpoint — only the
            // variables the inputCollection/loopCardinality expressions can
            // reference, not the whole instance scope.
            List<ProcessVariable> variables =
                rootVariablesFor(processInstanceId, mi.getInputCollection(), mi.getCardinality());
            collection = null;
            if (mi.getInputCollection() != null && !mi.getInputCollection().isBlank()) {
                collection = scriptService.evaluateExpression(mi.getInputCollection(), variables);
            }
            count = resolveCardinality(bpmnElement, variables, collection);
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: временная перегрузка пула — не ошибка модели.
            // Проброс до 503-хендлера (как главный путь ActivityService),
            // а не инцидент: неверные выражения по-прежнему идут в инцидент
            // ниже, семантика WO-REL-31 F23 для них не меняется ни на бит.
            throw e;
        } catch (EngineException e) {
            // WO-REL-31 F23: a failed cardinality resolution (fractional/out-of-int-range value,
            // missing characteristics) is an element failure — raise a visible incident on the
            // element, never abort the whole request: EngineException would otherwise propagate
            // through ActivityServiceImpl.execute as an abort.
            raiseCardinalityIncident(processInstanceId, token, bpmnElement, e.getMessage());
            return;
        }
        if (count == 0) {
            log.info("{}/{}: Multi-instance {} has zero instances, skipping", processInstanceId, token, bpmnElement.getId());
            // WO-DIFF-3 (#6): Zeebe/Raxon still create the output variable as
            // an empty array when the collection is empty — an absent variable
            // is not the same as an empty one for the external observer.
            writeEmptyOutputCollection(processInstanceId, mi);
            flowNavigator.proceedToOutgoing(processInstanceId, token, bpmnElement.getProcessDefinition(), bpmnElement, executor);
            return;
        }
        if (count < 0) {
            // F23: negative is invalid (the old intValue() path only reached count <= 0 here and
            // silently skipped — including for wrapped overflow values; the skip is reserved for 0).
            raiseCardinalityIncident(processInstanceId, token, bpmnElement,
                "Multi-instance " + bpmnElement.getId() + " cardinality must be non-negative: " + count);
            return;
        }
        if (count > MAX_MI_CARDINALITY) {
            // F23: cap synchronous spawn (covers loopCardinality AND inputCollection paths).
            raiseCardinalityIncident(processInstanceId, token, bpmnElement,
                "Multi-instance " + bpmnElement.getId() + " cardinality " + count
                    + " exceeds the engine limit of " + MAX_MI_CARDINALITY);
            return;
        }
        String miId = bpmnElement.getId();
        // WO-ENG-7: unique batch UUID per MI entry isolates join-bookkeeping between loop iterations.
        // Without this, every iteration reuses the same (processInstanceId, miId) key and data from
        // different iterations can collide (=done=true fires too early — the prod bug 52e6b64c).
        String batchUuid = UUID.randomUUID().toString();
        ProcessVariable batchVar = new ProcessVariable();
        batchVar.setName("_mi_batch_" + miId);
        batchVar.setType(ProcessVariableType.STRING);
        batchVar.setValue(batchUuid);
        dbService.setVariables(processInstanceId, List.of(batchVar));
        dbService.recordInclusiveExpected(processInstanceId, miId + "::" + batchUuid, count);
        int spawn = mi.isSequential() ? 1 : count;
        for (int i = 0; i < spawn; i++) {
            spawnMiInstance(processInstanceId, token, bpmnElement, mi, collection, i);
        }
        log.info("{}/{}: Entering multi-instance {}: {} {} instance(s) batch={}", processInstanceId, token, miId, count, mi.isSequential() ? "sequential" : "parallel", batchUuid);
    }

    /**
     * Records a multi-instance instance's completion and reports whether the whole multi-instance is done.
     * For sequential MI not yet done, the next instance is started here.
     */
    public boolean multiInstanceContinue(UUID processInstanceId, UUID token, BpmnElementModel element, UUID completedActivityId) {
        String miId = element.getId();
        // WO-ENG-7: resolve the per-entry batch UUID so bookkeeping is isolated between loop iterations
        String batchUuid = resolveBatchUuid(processInstanceId, miId);
        String gatewayKey = miId + "::" + batchUuid;
        MultiInstanceExtensionModel mi = element.getExtensions().getMultiInstanceExtension();
        dbService.recordParallelGatewayArrival(processInstanceId, gatewayKey, completedActivityId.toString());
        Integer expected = dbService.getInclusiveExpected(processInstanceId, gatewayKey);
        int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, gatewayKey).size();

        boolean done = (expected != null && arrived >= expected) || completionConditionMet(processInstanceId, arrived, expected, mi);
        if (done) {
            dbService.clearParallelGatewayArrivals(processInstanceId, gatewayKey);
            return true;
        }
        if (mi.isSequential()) {
            spawnMiInstance(processInstanceId, token, element, mi, miInputCollection(processInstanceId, mi), arrived);
            log.info("{}/{}: Multi-instance {} starting next sequential instance ({} of {} done)", processInstanceId, token, miId, arrived, expected);
        } else {
            log.info("{}: Multi-instance {} not ready: {} of {} instances done batch={}", processInstanceId, miId, arrived, expected, batchUuid);
        }
        return false;
    }

    /**
     * WO-DIFF-3 (#4): writes a multi-instance instance's outputElement into
     * the outputCollection POSITIONALLY — at the instance's
     * {@code loopCounter - 1} slot — not in completion order. Zeebe collects
     * by loopCounter index regardless of which iteration finishes first;
     * the old completion-order append is kept only for the ad-hoc path
     * ({@link ElementSupport#appendToJsonList}), which has no index.
     * No-op unless both outputCollection and outputElement are configured.
     */
    public void aggregateMultiInstanceOutput(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        if (mi == null || mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()
            || mi.getOutputElement() == null || mi.getOutputElement().isBlank()) {
            return;
        }
        // WO-PERF-9 (B-8, full-scan): the outputElement expression only needs
        // its own referenced names from the instance scope — one indexed
        // SELECT, not the root+scope merge over the whole scope.
        Object value = scriptService.evaluateExpression(mi.getOutputElement(),
            scopedVariablesFor(processInstanceId, scopeId, mi.getOutputElement()));
        dbService.setJsonElementAt(processInstanceId, mi.getOutputCollection(), miSlot(processInstanceId, scopeId) - 1,
            objectMapper.writeValueAsString(elementSupport.toJavaStructure(value)));
    }

    // ─── Private MI-only helpers ───────────────────────────────────────────

    private void spawnMiInstance(UUID processInstanceId, UUID token, BpmnElementModel element, MultiInstanceExtensionModel mi, Object collection, int index) {
        UUID activityId = dbService.createActivity(processInstanceId, token, element);
        bindMiInstanceVariables(processInstanceId, activityId, mi, collection, index);
        if (element.getType() == BpmnElementType.USER_TASK) {
            String resolvedAssignee = elementSupport.resolveAssignee(processInstanceId, element);
            String resolvedGroups = elementSupport.resolveCandidateGroups(processInstanceId, element);
            String formKey = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormKey() : null;
            // WO-C8-22: linked-form id rides its own field into the row (never into formKey).
            String formId = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormId() : null;
            // WO-C8-23: binding rides alongside (mirror of the UserTaskHandler path).
            String bindingType = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getBindingType() : null;
            String resolvedDueDate = elementSupport.resolveDueDate(processInstanceId, element);
            String resolvedFollowUpDate = elementSupport.resolveFollowUpDate(processInstanceId, element);
            // WO-C8-30: same resolve-or-incident as the UserTaskHandler path (mirror it —
            // MI instances resolve independently; a broken expression halts this
            // instance with an incident instead of a silent default).
            final int resolvedPriority;
            try {
                resolvedPriority = elementSupport.resolveUserTaskPriorityOrThrow(processInstanceId, element);
            } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
                // WO-ENG-24: см. cardinality-catch выше — перегрузка идёт до
                // 503-хендлера, битый priorityDefinition — в инцидент ниже.
                throw e;
            } catch (EngineException e) {
                log.warn("{}/{}: {}", processInstanceId, activityId, e.getMessage());
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, e.getMessage());
                return;
            }
            dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, formKey, formId, bindingType, resolvedDueDate, resolvedFollowUpDate, resolvedPriority);
        } else {
            dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(element), elementSupport.serviceTaskJob(element));
            elementSupport.applyIoMappings(processInstanceId, activityId, element, true);
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
        // WO-ENG-3: schedule boundary timers/messages/signals on each MI instance's activity
        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, element);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, element);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, element);
    }

    /**
     * Resolves the instance count from the already-fetched root variables and
     * the already-evaluated input collection (WO-REL-41, B-8 п.3 — the caller
     * shares both instead of re-fetching/re-evaluating on the same transition).
     * The sequential-continue path ({@link #multiInstanceContinue}) keeps its
     * own fetch via {@link #miInputCollection(UUID, MultiInstanceExtensionModel)} —
     * a different transition, not this one.
     */
    private int resolveCardinality(BpmnElementModel element, List<ProcessVariable> variables, Object collection) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElseThrow(() -> new EngineException("Multi-instance " + element.getId() + " has no loop characteristics"));

        if (mi.getInputCollection() != null && !mi.getInputCollection().isBlank()) {
            return collectionSize(collection, element.getId());
        }

        String expression = mi.getCardinality();
        if (expression == null || expression.isBlank()) {
            throw new EngineException("Multi-instance " + element.getId() + " has neither inputCollection nor loopCardinality");
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object value = scriptService.evaluateExpression(expression, variables);
        if (!(value instanceof Number number)) {
            throw new EngineException("Multi-instance " + element.getId() + " cardinality did not evaluate to a number: " + value);
        }
        // WO-REL-31 F23: Number.intValue() silently TRUNCATES fractions (2.5 → 2) and WRAPS
        // out-of-int-range values (3_000_000_000 → −1_294_967_296) — neither is a valid instance
        // count. intValueExact() on the exact decimal representation raises ArithmeticException
        // for both, which we turn into an EngineException (→ incident in enter()).
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException e) {
            throw new EngineException("Multi-instance " + element.getId() + " cardinality is not a valid whole number in int range: " + number);
        }
    }

    /**
     * F23: parks the token at the multi-instance element with a visible incident. Creates the
     * element's activity row first (precedent: spawnMiInstance's resolveUserTaskPriorityOrThrow
     * path) — IncidentService.raiseIncident would silently skip an element without an activity.
     */
    private void raiseCardinalityIncident(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, String message) {
        log.warn("{}/{}: {}", processInstanceId, token, message);
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }

    private int collectionSize(Object collection, String elementId) {
        if (collection instanceof java.util.Collection<?> c) {
            return c.size();
        }
        if (collection != null) {
            try {
                Object n = collection.getClass().getMethod("size").invoke(collection);
                if (n instanceof Integer size) {
                    return size;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        throw new EngineException("Multi-instance " + elementId + " inputCollection did not evaluate to a list: " + collection);
    }

    private Object miInputCollection(UUID processInstanceId, MultiInstanceExtensionModel mi) {
        if (mi.getInputElement() == null || mi.getInputElement().isBlank()
            || mi.getInputCollection() == null || mi.getInputCollection().isBlank()) {
            return null;
        }
        // WO-PERF-9 (B-8, full-scan): sequential-continue re-evaluates the
        // inputCollection on its own transition — pinpoint, same as enter().
        return scriptService.evaluateExpression(mi.getInputCollection(),
            rootVariablesFor(processInstanceId, mi.getInputCollection()));
    }

    private void bindMiInstanceVariables(UUID processInstanceId, UUID scopeId, MultiInstanceExtensionModel mi, Object collection, int index) {
        List<ProcessVariable> locals = new ArrayList<>();
        if (collection != null && mi.getInputElement() != null && !mi.getInputElement().isBlank()) {
            locals.add(elementSupport.toProcessVariable(mi.getInputElement(), collectionElement(collection, index)));
        }
        ProcessVariable loopCounter = new ProcessVariable();
        loopCounter.setName("loopCounter");
        loopCounter.setType(ProcessVariableType.LONG);
        loopCounter.setValue(Long.toString(index + 1L));
        locals.add(loopCounter);
        dbService.setVariables(processInstanceId, scopeId, locals);
    }

    private void appendToJsonList(UUID processInstanceId, String name, Object value) {
        // WO-C8-32: single shared mechanism in ElementSupport (ad-hoc output aggregation
        // reuses it); this delegate keeps MI behaviour byte-identical.
        elementSupport.appendToJsonList(processInstanceId, name, value);
    }

    /**
     * WO-DIFF-3 (#4): the completing instance's 1-based {@code loopCounter}
     * (written by {@link #bindMiInstanceVariables} at spawn). Sourced from
     * the instance's own scope — never from a stale root copy — so a
     * concurrent completion of a sibling cannot shift this write's slot.
     */
    private int miSlot(UUID processInstanceId, UUID scopeId) {
        // WO-PERF-9 (B-8, full-scan): only the loopCounter row is needed —
        // one indexed read, never the scope merge.
        return dbService.getScopedVariablesByNames(processInstanceId, scopeId, Set.of("loopCounter")).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> {
                try {
                    return Integer.parseInt(v.getValue().trim());
                } catch (NumberFormatException e) {
                    throw new EngineException("Multi-instance instance has a non-numeric loopCounter: " + v.getValue());
                }
            })
            .orElseThrow(() -> new EngineException("Multi-instance instance has no loopCounter in its scope"));
    }

    /**
     * WO-DIFF-3 (#6): creates the output variable as an empty JSON array when
     * the MI entry spawns zero instances. Only when outputCollection is
     * configured (no output name → nothing to create); never overwrites an
     * existing value (re-entry over a completed MI keeps prior results).
     */
    private void writeEmptyOutputCollection(UUID processInstanceId, MultiInstanceExtensionModel mi) {
        if (mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()) {
            return;
        }
        if (dbService.getVariableTextValue(processInstanceId, mi.getOutputCollection()).isPresent()) {
            return;
        }
        ProcessVariable empty = new ProcessVariable();
        empty.setName(mi.getOutputCollection());
        empty.setType(ProcessVariableType.JSON);
        empty.setValue("[]");
        dbService.setVariables(processInstanceId, List.of(empty));
    }

    private Object collectionElement(Object collection, int index) {
        if (collection instanceof java.util.List<?> list) {
            return index < list.size() ? list.get(index) : null;
        }
        if (collection != null) {
            try {
                return collection.getClass().getMethod("apply", int.class).invoke(collection, index);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    /**
     * Reads the per-entry batch UUID written by {@link #enter}. Falls back to {@code miId} for
     * legacy instances that were started before this fix (WO-ENG-7). The fallback is safe — it
     * merely opts out of iteration isolation for those instances (which may still suffer the old
     * bookkeeping collision, but that is pre-existing and will be resolved as those instances
     * complete their first MI entry).
     * <p>
     * WO-REL-41 (B-8, п.2): ONE pinpoint row read, not the full variable list.
     */
    private String resolveBatchUuid(UUID processInstanceId, String miId) {
        return dbService.getVariableTextValue(processInstanceId, "_mi_batch_" + miId)
            .orElse(miId);
    }

    private boolean completionConditionMet(UUID processInstanceId, int arrived, Integer expected, MultiInstanceExtensionModel mi) {
        String expression = mi.getCompletionCondition();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        // Build evaluation variables: process variables + MI-scope locals.
        // WO-ENG-8: inject completedInstances/totalInstances that the completionCondition may reference.
        // These are NOT persisted — they exist only for the duration of this FEEL evaluation.
        // NOTE: use evaluateExpression (not evaluateScript) because completionCondition is a FEEL expression,
        // not a unary test. evaluateScript would misinterpret the expression.
        // WO-PERF-9 (B-8, full-scan): the condition only needs its own
        // referenced names — one indexed SELECT, not the whole scope.
        List<ProcessVariable> variables = new ArrayList<>(rootVariablesFor(processInstanceId, expression));
        long total = expected != null ? expected : 0;
        long active = expected != null ? Math.max(0, expected - arrived) : 0;
        addLocalLong(variables, "completedInstances", arrived);
        addLocalLong(variables, "totalInstances", total);
        addLocalLong(variables, "numberOfInstances", total);
        addLocalLong(variables, "numberOfCompleteInstances", arrived);
        addLocalLong(variables, "numberOfActiveInstances", active);
        addLocalLong(variables, "numberOfTerminatedInstances", 0);
        Object result = scriptService.evaluateExpression(expression, variables);
        return Boolean.TRUE.equals(result);
    }

    /** Adds a long-valued ProcessVariable to the list for FEEL evaluation scope (not persisted). */
    private void addLocalLong(List<ProcessVariable> variables, String name, long value) {
        ProcessVariable var = new ProcessVariable();
        var.setName(name);
        var.setType(ProcessVariableType.LONG);
        var.setValue(Long.toString(value));
        variables.add(var);
    }
}
