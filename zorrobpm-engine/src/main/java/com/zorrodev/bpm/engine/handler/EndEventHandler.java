package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            activityService.finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn);
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class TerminateEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.TERMINATE_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Terminating instance at {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            dbService.cancelActiveActivities(ctx.processInstanceId());
            dbService.completeProcessInstance(ctx.processInstanceId());
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
