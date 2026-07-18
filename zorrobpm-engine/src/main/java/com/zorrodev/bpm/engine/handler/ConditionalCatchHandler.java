package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for CONDITIONAL_CATCH_EVENT elements.
 * If its FEEL condition already holds, passes through; otherwise parks the token.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionalCatchHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ScriptService scriptService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.CONDITIONAL_CATCH_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        if (conditionHolds(bpmnElement, variables)) {
            dbService.completeActivity(activityId);
            log.info("{}/{}: Conditional catch {} already true, passing through: {}", processInstanceId, tokenId, bpmnElement.getId(), activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, ctx.executor());
        } else {
            log.info("{}/{}: Waiting on condition at {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
        }
    }

    /**
     * Evaluates a conditional event's FEEL condition against the given variables.
     * Literal transfer from ActivityServiceImpl — no logic changes.
     */
    boolean conditionHolds(BpmnElementModel element, List<ProcessVariable> variables) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getExpression)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new EngineException("Conditional event " + element.getId() + " has no condition"));
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object result = scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(result);
    }
}
