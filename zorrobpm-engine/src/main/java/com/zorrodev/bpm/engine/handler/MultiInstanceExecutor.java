package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmnService bpmnService;
    private final ElementSupport elementSupport;
    private final BoundaryScheduler boundaryScheduler;

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
            String resolvedAssignee = elementSupport.resolveAssignee(processInstanceId, element);
            String resolvedGroups = elementSupport.resolveCandidateGroups(processInstanceId, element);
            String formKey = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormKey() : null;
            dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, formKey);
        } else {
            dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(element));
            elementSupport.applyIoMappings(processInstanceId, activityId, element, true);
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
        // WO-ENG-3: schedule boundary timers/messages/signals on each MI instance's activity
        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, element);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, element);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, element);
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
        List<Object> list = new ArrayList<>();
        ProcessVariable existing = dbService.getVariables(processInstanceId).stream()
            .filter(v -> v.getName().equals(name))
            .findFirst().orElse(null);
        if (existing != null && existing.getType() == ProcessVariableType.JSON
            && existing.getValue() != null && !existing.getValue().isBlank()
            && objectMapper.readValue(existing.getValue(), Object.class) instanceof List<?> current) {
            list.addAll(current);
        }
        list.add(elementSupport.toJavaStructure(value));

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
}
