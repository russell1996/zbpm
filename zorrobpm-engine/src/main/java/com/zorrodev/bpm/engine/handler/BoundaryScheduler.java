package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Registers boundary event subscriptions (signal, message, timer) for a host activity.
 * Extracted from ActivityServiceImpl — the last coupling between enterUserTask / enterServiceTask
 * entry paths, blocking UserTaskHandler extraction (AUD-18).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoundaryScheduler {

    private final DBService dbService;
    private final ElementSupport elementSupport;

    /**
     * Registers a signal subscription for every signal boundary event attached to the given host
     * activity. When such a signal is later broadcast the boundary fires.
     */
    public void scheduleSignalBoundaries(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
        BpmnProcessDefinitionModel pd = host.getProcessDefinition();
        if (pd == null) {
            return;
        }
        for (BpmnElementModel element : pd.getElements()) {
            if (element.getType() != BpmnElementType.SIGNAL_BOUNDARY_EVENT) {
                continue;
            }
            String attachedTo = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!host.getId().equals(attachedTo)) {
                continue;
            }
            String signalName = SignalCatchHandler.signalName(element);
            if (signalName == null) {
                throw new EngineException("Signal boundary " + element.getId() + " has no signal name");
            }
            dbService.createSignalSubscription(processInstanceId, hostActivityId, signalName, element.getId());
            log.info("{}: Signal boundary {} subscribed to '{}' on host activity {}", processInstanceId, element.getId(), signalName, hostActivityId);
        }
    }

    /**
     * Registers a message subscription for every message boundary event attached to the given host
     * activity. When such a message is later correlated the boundary fires.
     */
    public void scheduleMessageBoundaries(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
        BpmnProcessDefinitionModel pd = host.getProcessDefinition();
        if (pd == null) {
            return;
        }
        for (BpmnElementModel element : pd.getElements()) {
            if (element.getType() != BpmnElementType.MESSAGE_BOUNDARY_EVENT) {
                continue;
            }
            String attachedTo = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!host.getId().equals(attachedTo)) {
                continue;
            }
            String messageName = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getMessageEventExtension)
                .map(MessageEventExtensionModel::getMessageName)
                .orElseThrow(() -> new EngineException("Message boundary " + element.getId() + " has no message name"));
            String correlationKey = elementSupport.evaluateCorrelationKey(element, processInstanceId);
            dbService.createMessageSubscription(processInstanceId, hostActivityId, messageName, element.getId(), correlationKey);
            log.info("{}: Message boundary {} subscribed to '{}' (key {}) on host activity {}", processInstanceId, element.getId(), messageName, correlationKey, hostActivityId);
        }
    }

    /**
     * Schedules interrupting timer boundary jobs for any timer boundary event attached to the
     * given host activity. When such a timer fires before the host completes, the host is cancelled
     * and flow continues from the boundary's outgoing.
     */
    public void scheduleBoundaryTimers(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
        BpmnProcessDefinitionModel pd = host.getProcessDefinition();
        if (pd == null) {
            return;
        }
        for (BpmnElementModel element : pd.getElements()) {
            if (element.getType() != BpmnElementType.BOUNDARY_TIMER_EVENT) {
                continue;
            }
            String attachedTo = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (host.getId().equals(attachedTo)) {
                java.time.Instant dueAt = elementSupport.computeDueAt(element, processInstanceId);
                // WO-REL-17: the FIRST job of a repeating (timeCycle) boundary must carry the
                // persisted cycle expression and the remaining count (repeatCount - 1), exactly
                // like TimerCatchHandler does for catch timers. Otherwise the re-arm in
                // EventTrigger would see a pre-REL-14-style row (no expression) and end the
                // cycle after the first fire — and the bounded R<n> counter would never persist.
                Integer remainingCount = null;
                String expression = null;
                TimerEventExtensionModel timer = Optional.ofNullable(element.getExtensions())
                    .map(BpmnElementExtensionModel::getTimerEventExtension)
                    .orElse(null);
                if (timer != null && timer.getType() == TimerEventType.CYCLE) {
                    expression = timer.getExpression();
                    remainingCount = com.zorrodev.bpm.engine.scheduler.TimerExpressions.remainingCount(expression);
                }
                dbService.createTimerJob(hostActivityId, dueAt, element.getId(), remainingCount, expression, processInstanceId);
                log.info("{}: Boundary timer {} scheduled for {} on host activity {} (remaining={}, expression={})",
                    processInstanceId, element.getId(), dueAt, hostActivityId, remainingCount, expression);
            }
        }
    }
}
