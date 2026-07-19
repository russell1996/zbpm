package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
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
@RequiredArgsConstructor
public class ElementSupport {

    private final DBService dbService;
    private final ScriptService scriptService;
    private final FeelEngineApi feelEngineApi;
    private final tools.jackson.databind.ObjectMapper objectMapper;

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

    // ─── Boundary helpers ────────────────────────────────────────────

    public Instant computeDueAt(BpmnElementModel element) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId());
    }

    public Instant computeDueAt(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer, String elementId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Timer event " + elementId + " has no timer definition");
        }
        return switch (timer.getType()) {
            case DURATION -> Instant.now().plus(Duration.parse(timer.getExpression()));
            case DATE -> Instant.parse(timer.getExpression());
            case CYCLE -> com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(timer.getExpression(), Instant.now());
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
