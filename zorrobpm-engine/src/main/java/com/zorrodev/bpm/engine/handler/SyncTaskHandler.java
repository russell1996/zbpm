package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handlers for synchronous script and business-rule tasks.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
public class SyncTaskHandler {

    @Component
    @RequiredArgsConstructor
    public static class ScriptTask implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ScriptService scriptService;
        private final ElementSupport elementSupport;
        private final FlowNavigator flowNavigator;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.SCRIPT_TASK; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();

            // Camunda 8: a script task with a zeebe:taskDefinition runs as a job worker (like a service task)
            boolean jobWorker = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getServiceTaskExtension)
                .isPresent();
            if (jobWorker) {
                activityService.enterServiceTask(processInstanceId, tokenId, el);
                return;
            }

            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);

            ScriptTaskExtensionModel ext = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getScriptTaskExtension)
                .orElseThrow(() -> new IllegalStateException("Script task '" + el.getId() + "' has no script"));
            String script = ext.getScript();
            if (script == null || script.isBlank()) {
                throw new IllegalStateException("Script task '" + el.getId() + "' has an empty script");
            }

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            Object result = scriptService.evaluateExpression(script, variables);
            log.info("{}/{}: Script task {}: {}/{} evaluated to {}", processInstanceId, tokenId, el.getType(), activityId, el.getId(), result);

            String resultVariable = ext.getResultVariable();
            if (resultVariable != null && !resultVariable.isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(resultVariable, result)));
            }

            dbService.completeActivity(activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, el, ctx.executor());
            activityService.triggerConditionalEvents(processInstanceId);
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class BusinessRuleTask implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ScriptService scriptService;
        private final DmnService dmnService;
        private final ElementSupport elementSupport;
        private final FlowNavigator flowNavigator;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.BUSINESS_RULE_TASK; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();

            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);

            BusinessRuleExtensionModel ext = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getBusinessRuleExtension)
                .orElseThrow(() -> new IllegalStateException("Business rule task '" + el.getId() + "' has no decision or expression"));

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            Object result;
            if (ext.getDecisionId() != null && !ext.getDecisionId().isBlank()) {
                result = dmnService.evaluate(ext.getDecisionId(), variables);
            } else if (ext.getExpression() != null && !ext.getExpression().isBlank()) {
                String expression = ext.getExpression().startsWith("=") ? ext.getExpression().substring(1) : ext.getExpression();
                result = scriptService.evaluateExpression(expression, variables);
            } else {
                throw new IllegalStateException("Business rule task '" + el.getId() + "' has neither a decision nor an expression");
            }
            log.info("{}/{}: Business rule task {}: {}/{} evaluated to {}", processInstanceId, tokenId, el.getType(), activityId, el.getId(), result);

            if (ext.getResultVariable() != null && !ext.getResultVariable().isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(ext.getResultVariable(), result)));
            }

            dbService.completeActivity(activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, el, ctx.executor());
        }
    }
}
