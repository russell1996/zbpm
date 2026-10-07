package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handlers for terminal end-event BPMN elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 * Each inner class handles one element type and registers via {@link TypedElementHandler}.
 */
@Slf4j
@Component
public class EndEventHandler {

    @Component
    @RequiredArgsConstructor
    public static class EndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            // WO-C8-37 (C37-1): a plain end inside a LIVE ad-hoc scope is a chain-end
            // arrival, not a scope exit — the ad-hoc join owns completion (counter +
            // quiescence) and consumes the branch when the scope actually finished.
            // A scope that just finished routes the END THROUGH the join (arrival
            // recorded, SCOPE_FINISHED consumed) so the join's incoming-token
            // continuation — not a scope-token re-entry — carries the branch.
            // Otherwise (no live ad-hoc scope on this token — the common linear
            // case, and any end on a scope token whose scope already closed) the
            // historical finishBranch path ends the branch/instance, exactly as before.
            Token token = dbService.findToken(ctx.tokenId()).orElse(null);
            if (token != null && token.getScopeActivityId() != null) {
                Activity scope = dbService.getActivity(token.getScopeActivityId());
                if (scope != null
                    && scope.getType() == BpmnElementType.AD_HOC_SUB_PROCESS
                    && (scope.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
                        || scope.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS)) {
                    if (flowNavigator.handleAdHocArrival(ctx.processInstanceId(), ctx.tokenId(), bpmn, el, ctx.executor())
                        == ArrivalOutcome.SCOPE_FINISHED) {
                        return;
                    }
                    // Live scope, join not finished: the end element's own chain is
                    // over — park silently (the scope join will consume the scope
                    // when its counter/quiescence says done). Flowing into
                    // finishBranch on the scope token would mis-close the scope
                    // (SUB-path completes the container off the join's control).
                    return;
                }
            }
            flowNavigator.finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn, ctx.executor());
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class TerminateEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;
        private final ElementSupport elementSupport;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.TERMINATE_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Terminating instance at {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            // WO-C8-34 (CR-02): terminate is scope-confined. In the ROOT scope the
            // whole instance ends, as before. Inside an embedded subprocess only
            // this scope's activities are cancelled and the subprocess container
            // is closed, then the parent flow continues from its outgoing flows —
            // parallel branches OUTSIDE the scope survive (external review repro).
            Token token = dbService.findToken(ctx.tokenId()).orElse(null);
            if (token == null || token.getScopeActivityId() == null) {
                dbService.cancelActiveActivities(ctx.processInstanceId());
                dbService.completeProcessInstance(ctx.processInstanceId());
                return;
            }
            UUID scopeActivityId = token.getScopeActivityId();
            List<Activity> inScope = elementSupport.filterActivitiesInScope(
                ctx.processInstanceId(), dbService.getActiveActivities(ctx.processInstanceId()), scopeActivityId);
            for (Activity active : inScope) {
                dbService.cancelActivity(active.getId());
            }
            Activity scope = dbService.getActivity(scopeActivityId);
            // WO-C8-37 (C37-4): terminate closes the scope like a normal exit —
            // the container's output mappings are promoted to root and the scope
            // locals are dropped, exactly as FlowNavigator.finishBranch does
            // (same gate: SUB_PROCESS with an ioMapping extension; otherwise zero
            // behaviour change). Row status stays CANCELLED (terminate semantic).
            BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
            if (scopeElement != null
                && scopeElement.getType() == BpmnElementType.SUB_PROCESS
                && scopeElement.getExtensions() != null
                && scopeElement.getExtensions().getIoMappingExtension() != null) {
                elementSupport.applyIoMappings(ctx.processInstanceId(), scopeActivityId, scopeElement, false);
                dbService.deleteVariables(ctx.processInstanceId(), scopeActivityId);
            }
            dbService.cancelActivity(scopeActivityId);
            flowNavigator.proceedToOutgoing(ctx.processInstanceId(), token.getParentId(), bpmn, scopeElement, ctx.executor());
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class ErrorEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.ERROR_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);

            String errorCode = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);

            log.info("{}/{}: Error end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), errorCode, activityId);

            boolean handled = activityService.throwError(ctx.processInstanceId(), ctx.tokenId(), errorCode);
            if (!handled) {
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, "Unhandled BPMN error" + (errorCode != null ? " '" + errorCode + "'" : ""));
            }
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class EscalationEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.ESCALATION_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);

            String escalationCode = activityService.escalationCode(el);
            log.info("{}/{}: Escalation end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), escalationCode, activityId);

            boolean interrupted = activityService.throwEscalation(ctx.processInstanceId(), ctx.tokenId(), escalationCode);
            if (!interrupted) {
                activityService.finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn);
            }
        }
    }
}
