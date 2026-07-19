package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handlers for start events and escalation throw event.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
public class StartThrowEventHandler {

    @Component
    @RequiredArgsConstructor
    public static class StartEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.START_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            flowNavigator.proceedToOutgoing(ctx.processInstanceId(), ctx.tokenId(), bpmn, el, ctx.executor());
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class EscalationThrowEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.ESCALATION_THROW_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);

            String escalationCode = activityService.escalationCode(el);
            log.info("{}/{}: Escalation throw {} (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), escalationCode, activityId);

            boolean interrupted = activityService.throwEscalation(ctx.processInstanceId(), ctx.tokenId(), escalationCode);
            if (!interrupted) {
                flowNavigator.proceedToOutgoing(ctx.processInstanceId(), ctx.tokenId(), bpmn, el, ctx.executor());
            }
        }
    }
}
