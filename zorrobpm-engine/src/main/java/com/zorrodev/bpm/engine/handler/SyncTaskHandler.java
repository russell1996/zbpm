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
            // WO-SEC-53 (E6): the evaluated VALUE is deliberately not logged — script results
            // can carry PII/business secrets (same reasoning as WO-SEC-29, which downgraded
            // this from INFO to DEBUG). Even a DEBUG line would leak it if DEBUG is ever
            // enabled in prod; the execution fact alone is enough for diagnostics.
            log.debug("{}/{}: Script task {}: {}/{} evaluated", processInstanceId, tokenId, el.getType(), activityId, el.getId());

            String resultVariable = ext.getResultVariable();
            if (resultVariable != null && !resultVariable.isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(resultVariable, result)));
            }

            dbService.completeActivity(activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, el, ctx.executor());
            activityService.triggerConditionalEvents(processInstanceId);
        }
    }

    /**
     * Handler for MANUAL_TASK elements (WO-C8-5): pass-through flow node, like a none event.
     * Camunda 8 runs nothing automatically for a manual task (no job worker, no engine-driven
     * form) — enter and immediately leave, no side effects beyond the completed activity itself.
     * Tail of {@link ScriptTask#handle} without the script evaluation in the middle.
     */
    @Component
    @RequiredArgsConstructor
    public static class ManualTask implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.MANUAL_TASK; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();

            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);
            dbService.completeActivity(activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, el, ctx.executor());
            activityService.triggerConditionalEvents(processInstanceId);
        }
    }

    /**
     * Handler for UNDEFINED_TASK elements (WO-DIFF-9): a bare {@code <bpmn:task>}
     * with no concrete type selected in the modeler. Real Zeebe 8.5 treats it as a
     * no-op pass-through (verified live) — body identical to {@link ManualTask},
     * separate type so diagnostics tell "forgotten type" apart from "real manual task".
     */
    @Component
    @RequiredArgsConstructor
    public static class UndefinedTask implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.UNDEFINED_TASK; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();

            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);
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
                // WO-C8-2: decisionId may be a FEEL expression — resolve like processId in
                // CallActivityHandler. Null/blank after resolve is an explicit error naming the
                // failed expression, never a silent NPE inside dmnService.evaluate.
                String decisionId = elementSupport.resolveExpression(ext.getDecisionId(), processInstanceId);
                if (decisionId == null || decisionId.isBlank()) {
                    throw new IllegalStateException("Business rule task '" + el.getId() + "' decisionId expression '" + ext.getDecisionId() + "' resolved to null/blank — check instance variables and FEEL syntax");
                }
                // WO-C8-17: bindingType="deployment" pins the decision version deployed together
                // with the currently running process version; "latest"/absent/anything else keeps
                // the historical latest-wins path byte-identical. versionTag is consumed by the
                // WO-C8-20 branch below.
                if ("deployment".equals(ext.getBindingType())) {
                    UUID processDefinitionId = dbService.getProcessInstance(processInstanceId).getProcessDefinitionId();
                    result = dmnService.evaluate(decisionId, variables, processDefinitionId);
                } else if ("versionTag".equals(ext.getBindingType())) {
                    // WO-C8-20: pin to the latest deployed version annotated with the tag.
                    // A missing tag attribute is an explicit error (mirror CallActivityHandler's
                    // versionTag branch from WO-C8-3), not a silent null tag lookup.
                    String tag = ext.getVersionTag();
                    if (tag == null || tag.isBlank()) {
                        throw new IllegalStateException("Business rule task '" + el.getId() + "' has bindingType=\"versionTag\" but no versionTag attribute");
                    }
                    result = dmnService.evaluateByVersionTag(decisionId, variables, tag);
                } else {
                    // WO-DIFF-8: an undeployed decision is a CALLED_DECISION_ERROR incident
                    // (Zeebe/Raxon S-059 parity: the process stalls, the start is NOT
                    // rejected with 422). IllegalStateException — not EngineException — is
                    // deliberate: ActivityServiceImpl.execute rethrows EngineException as an
                    // abort (HTTP 422), while any other exception parks the token as an
                    // incident — the same mechanics as the deployment/versionTag branches
                    // above. dmnService.evaluate keeps its EngineException contract
                    // untouched (REST POST /dmn/{id}/evaluate maps it to 400, existing DMN
                    // tests pin it) — the probe decides before the shared method is called.
                    if (!dmnService.decisionExists(decisionId)) {
                        // WO-DIFF-8: broker-verbatim message (Raxon WO-028 differential probe:
                        // "Expected to evaluate decision 'X', but no decision found for id 'X'"
                        // with errorType CALLED_DECISION_ERROR).
                        throw new IllegalStateException("Expected to evaluate decision '" + decisionId
                            + "', but no decision found for id '" + decisionId + "'");
                    }
                    result = dmnService.evaluate(decisionId, variables);
                }
            } else if (ext.getExpression() != null && !ext.getExpression().isBlank()) {
                String expression = ext.getExpression().startsWith("=") ? ext.getExpression().substring(1) : ext.getExpression();
                result = scriptService.evaluateExpression(expression, variables);
            } else {
                throw new IllegalStateException("Business rule task '" + el.getId() + "' has neither a decision nor an expression");
            }
            // WO-SEC-53 (E6): same as above — result value not logged (PII).
            log.debug("{}/{}: Business rule task {}: {}/{} evaluated", processInstanceId, tokenId, el.getType(), activityId, el.getId());

            if (ext.getResultVariable() != null && !ext.getResultVariable().isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(ext.getResultVariable(), result)));
            }

            dbService.completeActivity(activityId);
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, el, ctx.executor());
        }
    }
}
