package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler for MESSAGE_CATCH_EVENT and RECEIVE_TASK elements.
 * Parks the token and registers a message subscription.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageCatchHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ActivityService activityService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.MESSAGE_CATCH_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        String messageName = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getMessageName)
            .orElseThrow(() -> new EngineException("Message catch event " + bpmnElement.getId() + " has no message name"));
        String correlationKey = activityService.evaluateCorrelationKey(bpmnElement, processInstanceId);
        dbService.createMessageSubscription(processInstanceId, activityId, messageName, null, correlationKey);
        log.info("{}/{}: Subscribed to message '{}' (key {}) at {}: {}/{}", processInstanceId, tokenId, messageName, correlationKey, bpmnElement.getType(), activityId, bpmnElement.getId());
    }
}
