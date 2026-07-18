package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final FeelEngineApi feelEngineApi;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmnService bpmnService;

    private FlowNavigator flowNavigator;

    @PostConstruct
    void init() {
        flowNavigator = new FlowNavigator(dbService, bpmnService, scriptService);
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
        int count = resolveCardinality(processInstanceId, bpmnElement);
        if (count <= 0) {
            log.info("{}/{}: Multi-instance {} has zero instances, skipping", processInstanceId, token, bpmnElement.getId());
            flowNavigator.proceedToOutgoing(processInstanceId, token, bpmnElement.getProcessDefinition(), bpmnElement, executor);
            return;
        }
        dbService.recordInclusiveExpected(processInstanceId, bpmnElement.getId(), count);
        Object collection = miInputCollection(processInstanceId, mi);
        int spawn = mi.isSequential() ? 1 : count;
        for (int i = 0; i < spawn; i++) {
            spawnMiInstance(processInstanceId, token, bpmnElement, mi, collection, i);
        }
        log.info("{}/{}: Entering multi-instance {}: {} {} instance(s)", processInstanceId, token, bpmnElement.getId(), count, mi.isSequential() ? "sequential" : "parallel");
    }

    /**
     * Records a multi-instance instance's completion and reports whether the whole multi-instance is done.
     * For sequential MI not yet done, the next instance is started here.
     */
    public boolean multiInstanceContinue(UUID processInstanceId, UUID token, BpmnElementModel element, UUID completedActivityId) {
        String miId = element.getId();
        MultiInstanceExtensionModel mi = element.getExtensions().getMultiInstanceExtension();
        dbService.recordParallelGatewayArrival(processInstanceId, miId, completedActivityId.toString());
        Integer expected = dbService.getInclusiveExpected(processInstanceId, miId);
        int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, miId).size();

        boolean done = (expected != null && arrived >= expected) || completionConditionMet(processInstanceId, mi);
        if (done) {
            dbService.clearParallelGatewayArrivals(processInstanceId, miId);
            return true;
        }
        if (mi.isSequential()) {
            spawnMiInstance(processInstanceId, token, element, mi, miInputCollection(processInstanceId, mi), arrived);
            log.info("{}/{}: Multi-instance {} starting next sequential instance ({} of {} done)", processInstanceId, token, miId, arrived, expected);
        } else {
            log.info("{}: Multi-instance {} not ready: {} of {} instances done", processInstanceId, miId, arrived, expected);
        }
        return false;
    }

    /** Appends a multi-instance instance's outputElement to the outputCollection. No-op unless both are configured. */
    public void aggregateMultiInstanceOutput(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        if (mi == null || mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()
            || mi.getOutputElement() == null || mi.getOutputElement().isBlank()) {
            return;
        }
        Object value = scriptService.evaluateExpression(mi.getOutputElement(), dbService.getVariables(processInstanceId, scopeId));
        appendToJsonList(processInstanceId, mi.getOutputCollection(), value);
    }

    // ─── Private MI-only helpers ───────────────────────────────────────────

    private void spawnMiInstance(UUID processInstanceId, UUID token, BpmnElementModel element, MultiInstanceExtensionModel mi, Object collection, int index) {
        UUID activityId = dbService.createActivity(processInstanceId, token, element);
        bindMiInstanceVariables(processInstanceId, activityId, mi, collection, index);
        if (element.getType() == BpmnElementType.USER_TASK) {
            String resolvedAssignee = resolveAssignee(processInstanceId, element);
            String resolvedGroups = resolveCandidateGroups(processInstanceId, element);
            String formKey = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormKey() : null;
            dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, formKey);
        } else {
            dbService.createServiceTask(activityId, serviceTaskRetries(element));
            applyIoMappings(processInstanceId, activityId, element, true);
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
    }

    private int resolveCardinality(UUID processInstanceId, BpmnElementModel element) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElseThrow(() -> new EngineException("Multi-instance " + element.getId() + " has no loop characteristics"));
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);

        if (mi.getInputCollection() != null && !mi.getInputCollection().isBlank()) {
            Object collection = scriptService.evaluateExpression(mi.getInputCollection(), variables);
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
        return number.intValue();
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
        return scriptService.evaluateExpression(mi.getInputCollection(), dbService.getVariables(processInstanceId));
    }

    private void bindMiInstanceVariables(UUID processInstanceId, UUID scopeId, MultiInstanceExtensionModel mi, Object collection, int index) {
        List<ProcessVariable> locals = new ArrayList<>();
        if (collection != null && mi.getInputElement() != null && !mi.getInputElement().isBlank()) {
            locals.add(toProcessVariable(mi.getInputElement(), collectionElement(collection, index)));
        }
        ProcessVariable loopCounter = new ProcessVariable();
        loopCounter.setName("loopCounter");
        loopCounter.setType(ProcessVariableType.LONG);
        loopCounter.setValue(Long.toString(index + 1L));
        locals.add(loopCounter);
        dbService.setVariables(processInstanceId, scopeId, locals);
    }

    private void appendToJsonList(UUID processInstanceId, String name, Object value) {
        List<Object> list = new ArrayList<>();
        ProcessVariable existing = dbService.getVariables(processInstanceId).stream()
            .filter(v -> v.getName().equals(name))
            .findFirst().orElse(null);
        if (existing != null && existing.getType() == ProcessVariableType.JSON
            && existing.getValue() != null && !existing.getValue().isBlank()
            && objectMapper.readValue(existing.getValue(), Object.class) instanceof List<?> current) {
            list.addAll(current);
        }
        list.add(toJavaStructure(value));

        ProcessVariable out = new ProcessVariable();
        out.setName(name);
        out.setType(ProcessVariableType.JSON);
        out.setValue(objectMapper.writeValueAsString(list));
        dbService.setVariables(processInstanceId, List.of(out));
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

    private boolean completionConditionMet(UUID processInstanceId, MultiInstanceExtensionModel mi) {
        String expression = mi.getCompletionCondition();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object result = scriptService.evaluateScript(expression, dbService.getVariables(processInstanceId));
        return Boolean.TRUE.equals(result);
    }

    // ─── Shared utilities (same logic as ActivityServiceImpl, avoiding interface changes) ───

    private String resolveAssignee(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssignee)
            .orElse(null);
        return resolveExpression(raw, processInstanceId);
    }

    private String resolveCandidateGroups(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateGroups)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    private String resolveExpression(String raw, UUID processInstanceId) {
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

    private Map<String, Object> variablesToMap(UUID processInstanceId) {
        List<ProcessVariable> vars = dbService.getVariables(processInstanceId);
        Map<String, Object> map = new HashMap<>();
        for (ProcessVariable v : vars) {
            map.put(v.getName(), v.getValue());
        }
        return map;
    }

    private int serviceTaskRetries(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ext -> ext.getRetries())
            .filter(r -> r != null && r > 0)
            .orElse(3);
    }

    private void applyIoMappings(UUID processInstanceId, UUID activityId, BpmnElementModel element, boolean inputs) {
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
            if (mapping.getSource() == null || mapping.getTarget() == null || mapping.getTarget().isBlank()) {
                continue;
            }
            String expression = mapping.getSource().startsWith("=") ? mapping.getSource().substring(1) : mapping.getSource();
            Object value = scriptService.evaluateExpression(expression, variables);
            results.add(toProcessVariable(mapping.getTarget(), value));
        }
        if (!results.isEmpty()) {
            dbService.setVariables(processInstanceId, inputs ? activityId : null, results);
            log.info("{}: Applied {} {} mapping(s) at {} (scope {})", processInstanceId, results.size(), inputs ? "input" : "output", element.getId(), inputs ? activityId : "root");
        }
    }

    private ProcessVariable toProcessVariable(String name, Object result) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        if (result instanceof Boolean b) {
            v.setType(ProcessVariableType.BOOLEAN);
            v.setValue(b.toString());
        } else if (result instanceof Number number && isIntegral(number)) {
            v.setType(ProcessVariableType.LONG);
            v.setValue(Long.toString(number.longValue()));
        } else if (result instanceof Number number) {
            java.math.BigDecimal bd = (number instanceof java.math.BigDecimal x)
                ? x : java.math.BigDecimal.valueOf(number.doubleValue());
            v.setType(ProcessVariableType.DOUBLE);
            v.setValue(bd.toPlainString());
        } else if (isStructuredResult(result)) {
            v.setType(ProcessVariableType.JSON);
            v.setValue(objectMapper.writeValueAsString(toJavaStructure(result)));
        } else {
            v.setType(ProcessVariableType.STRING);
            v.setValue(result == null ? "" : result.toString());
        }
        return v;
    }

    private boolean isIntegral(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return true;
        }
        if (number instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        double d = number.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    private boolean isStructuredResult(Object v) {
        return v instanceof java.util.Map || v instanceof java.util.List
            || v instanceof scala.collection.Map || v instanceof scala.collection.Iterable;
    }

    private Object toJavaStructure(Object v) {
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
