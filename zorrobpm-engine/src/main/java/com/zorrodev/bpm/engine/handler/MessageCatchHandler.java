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
 *
 * <p>WO-C8-37 (C37-2): message catch hosts arm boundary events on entry, like
 * the other async wait-state hosts (UserTask/ServiceTask pattern). Before this
 * a timer/message/signal boundary on a {@code receiveTask} never produced its
 * row and never fired.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageCatchHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ActivityService activityService;
    private final BoundaryScheduler boundaryScheduler;

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
        // WO-C8-37 (C37-2): arm boundaries on the wait-state row (same trio and
        // relative order as UserTaskHandler.postCreation) — the host parks below,
        // so a later fire finds it alive; fireBoundary self-skips finished hosts.
        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, bpmnElement);
        log.info("{}/{}: Subscribed to message '{}' (key {}) at {}: {}/{}", processInstanceId, tokenId, messageName, correlationKey, bpmnElement.getType(), activityId, bpmnElement.getId());
    }
}
