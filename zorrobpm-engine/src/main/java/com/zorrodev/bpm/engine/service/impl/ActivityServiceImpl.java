package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final DmnService dmnService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Handler for a single BPMN element type. Method references capture {@code this} lazily,
     * so building the registry as a field initializer is safe even before the injected
     * dependencies are assigned by the generated constructor.
     */
    @FunctionalInterface
    private interface ElementHandler {
        void handle(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element);
    }

    /**
     * Maximum number of nested {@link #execute} calls for a single triggering request.
     * Guards against unbounded control-flow loops and self/mutually-recursive call activities
     * that would otherwise grow the call stack until {@link StackOverflowError}.
     */
    @Value("${zorrobpm.engine.max-execution-depth:1000}")
    private int maxExecutionDepth = 1000;

    private final ThreadLocal<Integer> executionDepth = ThreadLocal.withInitial(() -> 0);
    // re-entrancy guard: a fired conditional event's own continuation must not re-trigger evaluation
    private final ThreadLocal<Boolean> evaluatingConditionals = ThreadLocal.withInitial(() -> false);

    private final Map<BpmnElementType, ElementHandler> handlers = createHandlers();

    private Map<BpmnElementType, ElementHandler> createHandlers() {
        Map<BpmnElementType, ElementHandler> map = new EnumMap<>(BpmnElementType.class);
        map.put(BpmnElementType.START_EVENT, (pi, t, bpmn, el) -> processStartEvent(pi, t, bpmn, el));
        // message/timer start events, once triggered, behave like a plain start: complete and continue
        map.put(BpmnElementType.MESSAGE_START_EVENT, this::processStartEvent);
        map.put(BpmnElementType.TIMER_START_EVENT, this::processStartEvent);
        map.put(BpmnElementType.SIGNAL_START_EVENT, this::processStartEvent);
        map.put(BpmnElementType.END_EVENT, this::processEndEvent);
        map.put(BpmnElementType.TERMINATE_END_EVENT, this::processTerminateEnd);
        map.put(BpmnElementType.ERROR_END_EVENT, this::processErrorEnd);
        map.put(BpmnElementType.ESCALATION_END_EVENT, this::processEscalationEnd);
        map.put(BpmnElementType.ESCALATION_THROW_EVENT, this::processEscalationThrow);
        map.put(BpmnElementType.SERVICE_TASK, (pi, t, bpmn, el) -> enterServiceTask(pi, t, el));
        // Script task = synchronous inline FEEL evaluation; the result is written to a process variable.
        map.put(BpmnElementType.SCRIPT_TASK, this::processScriptTask);
        // Business rule task = evaluate a DMN decision or an inline FEEL expression, store the result.
        map.put(BpmnElementType.BUSINESS_RULE_TASK, this::processBusinessRuleTask);
        map.put(BpmnElementType.USER_TASK, (pi, t, bpmn, el) -> enterUserTask(pi, t, el));
        // Send task: a zeebe:taskDefinition makes it a job worker (Camunda 8), otherwise it is a message
        // throw in task form. Receive task = message catch (wait state) in task form.
        map.put(BpmnElementType.SEND_TASK, this::processSendTask);
        map.put(BpmnElementType.RECEIVE_TASK, (pi, t, bpmn, el) -> enterMessageCatch(pi, t, el));
        map.put(BpmnElementType.EXCLUSIVE_GATEWAY, this::processExclusiveGateway);
        map.put(BpmnElementType.PARALLEL_GATEWAY, this::processParallelGateway);
        map.put(BpmnElementType.EVENT_BASED_GATEWAY, this::processEventBasedGateway);
        map.put(BpmnElementType.INCLUSIVE_GATEWAY, this::processInclusiveGateway);
        map.put(BpmnElementType.CALL_ACTIVITY, this::processCallActivity);
        map.put(BpmnElementType.SUB_PROCESS, this::processSubProcess);
        // Catch events are wait states: the token parks here until an external trigger
        // (timer fires / message correlated) resumes it via signal(...). Until the timer
        // and message subsystems land, these elements at least park cleanly with an active
        // activity instead of silently falling through to "Unsupported".
        map.put(BpmnElementType.INTERMEDIATE_CATCH_EVENT, (pi, t, bpmn, el) -> enterWaitState(pi, t, el));
        map.put(BpmnElementType.MESSAGE_CATCH_EVENT, (pi, t, bpmn, el) -> enterMessageCatch(pi, t, el));
        map.put(BpmnElementType.TIMER_CATCH_EVENT, (pi, t, bpmn, el) -> enterTimerCatch(pi, t, el));
        // Conditional catch: passes through if its FEEL condition already holds, otherwise parks until a
        // variable change re-evaluates it to true (see triggerConditionalEvents).
        map.put(BpmnElementType.CONDITIONAL_CATCH_EVENT, this::enterConditionalCatch);
        // Throw events are pass-through: a plain intermediate throw has no side effect and simply
        // continues. (Message throw publishing is added with the message subsystem.)
        map.put(BpmnElementType.INTERMEDIATE_THROW_EVENT, this::processThrowEvent);
        map.put(BpmnElementType.MESSAGE_THROW_EVENT, this::processMessageThrow);
        // Signal catch parks and subscribes; signal throw broadcasts to all active subscribers (1:N).
        map.put(BpmnElementType.SIGNAL_CATCH_EVENT, (pi, t, bpmn, el) -> enterSignalCatch(pi, t, el));
        map.put(BpmnElementType.SIGNAL_THROW_EVENT, this::processSignalThrow);
        // Link throw jumps to the matching link catch (an intra-process goto); the catch is a pass-through.
        map.put(BpmnElementType.LINK_THROW_EVENT, this::processLinkThrow);
        map.put(BpmnElementType.LINK_CATCH_EVENT, this::processStartEvent);
        // Compensation throw runs the compensation handlers of completed compensation-bounded activities.
        map.put(BpmnElementType.COMPENSATION_THROW_EVENT, this::processCompensationThrow);
        // Cancel end (inside a transaction) compensates the transaction and routes to its cancel boundary.
        map.put(BpmnElementType.CANCEL_END_EVENT, this::processCancelEnd);
        return map;
    }

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     */
    private void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element.getOutgoing() == null) {
            return; // a dead end (e.g. a compensation handler off the main flow has no outgoing flow)
        }
        for (String outgoing : element.getOutgoing()) {
            processFlow(processInstanceId, tokenId, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            if (flow == null) {
                throw new IllegalStateException("Sequence flow '" + outgoing + "' not found in the process definition");
            }
            BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
            if (target == null) {
                throw new IllegalStateException("Target element '" + flow.getTargetRef() + "' of sequence flow '" + outgoing + "' not found in the process definition");
            }
            execute(processInstanceId, tokenId, bpmn, target);
        }
    }

    /**
     * Parks the token at a catch/wait element: records an active activity and stops. The token
     * stays here until {@link #signal(UUID, List)} is called for the created activity.
     */
    private void processThrowEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /**
     * Link throw: an intra-process "goto". Completes the throw activity, then continues from the
     * matching link catch (same link name) — there is exactly one catch per link name. A throw with
     * no matching catch is an incident (raised by the {@code orElseThrow}).
     */
    private void processLinkThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String linkName = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getName)
            .orElse(null);

        BpmnElementModel catchEvent = findLinkCatch(bpmn, linkName);
        if (catchEvent == null) {
            throw new IllegalStateException("Link throw '" + bpmnElement.getId()
                + "' has no matching link catch for link '" + linkName + "'");
        }
        log.info("{}/{}: Link throw {} -> catch {} (link '{}')", processInstanceId, tokenId, bpmnElement.getId(), catchEvent.getId(), linkName);

        // jump to the catch: execute it (records the catch activity and continues from its outgoing)
        execute(processInstanceId, tokenId, bpmn, catchEvent);
    }

    /**
     * Compensation throw: completes its own activity, then runs the compensation handler of every completed
     * compensation-bounded activity in the instance, in reverse order, before continuing down its outgoing
     * flow. Order is by activity creation time descending — for a sequential flow this is the reverse of the
     * completion order, as BPMN requires. Each handler (off the main flow, linked by an {@code <association>})
     * is executed synchronously on the throw's token.
     *
     * <p>Scope: "compensate all in the process scope" via an intermediate compensation throw, with
     * synchronously-executable handlers. Targeted (activityRef) compensation, compensation end events and
     * compensation within a subprocess scope are not yet supported.
     */
    private void processCompensationThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        // activityRef targets a single activity to compensate; null = compensate every completed activity
        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getReference)
            .orElse(null);
        log.info("{}/{}: Compensation throw {} at {} (target {})", processInstanceId, tokenId, bpmnElement.getId(), activityId, activityRef == null ? "all" : activityRef);

        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (activityRef != null) {
            targets = targets.stream().filter(a -> activityRef.equals(a.getBpmnElementId())).toList();
        }
        runCompensation(processInstanceId, tokenId, bpmn, targets);

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /**
     * Runs the compensation handler of every candidate activity that has a compensation boundary, in
     * reverse order (by activity creation time descending — the reverse of completion order for a
     * sequential flow). Each handler runs synchronously on {@code runToken}. Shared by the compensation
     * throw event and transaction cancellation.
     */
    private void runCompensation(UUID processInstanceId, UUID runToken, BpmnProcessDefinitionModel bpmn, List<Activity> candidates) {
        List<Activity> completed = new ArrayList<>(candidates);
        completed.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        for (Activity activity : completed) {
            BpmnElementModel boundary = findCompensationBoundary(bpmn, activity.getBpmnElementId());
            if (boundary == null) {
                continue;
            }
            String handlerId = Optional.ofNullable(boundary.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getCompensationHandlerId)
                .orElse(null);
            BpmnElementModel handler = handlerId == null ? null : bpmn.getElement(handlerId);
            if (handler == null) {
                continue;
            }
            log.info("{}/{}: Compensating {} via handler {}", processInstanceId, runToken, activity.getBpmnElementId(), handlerId);
            execute(processInstanceId, runToken, bpmn, handler);
        }
    }

    /**
     * Cancel end event inside a transaction: compensates the transaction's completed activities (in reverse
     * order), cancels the transaction scope, and continues from the transaction's cancel boundary. A cancel
     * end outside a transaction scope falls back to a plain end.
     *
     * <p>Scope: cancel end + cancel boundary on a top-level transaction sub-process. Cancel propagation
     * across nested transactions is not yet supported.
     */
    private void processCancelEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        Token endToken = dbService.getToken(tokenId);
        if (endToken == null || endToken.getScopeActivityId() == null) {
            log.info("{}/{}: Cancel end {} outside a transaction scope, ending branch", processInstanceId, tokenId, bpmnElement.getId());
            finishBranch(processInstanceId, tokenId, bpmn);
            return;
        }

        UUID scopeActivityId = endToken.getScopeActivityId();
        Activity scope = dbService.getActivity(scopeActivityId);
        BpmnElementModel transaction = bpmn.getElement(scope.getBpmnElementId());
        UUID parentToken = endToken.getParentId();
        log.info("{}/{}: Cancel end {} cancelling transaction {}", processInstanceId, tokenId, bpmnElement.getId(), transaction.getId());

        // compensate the transaction's completed activities (those carried by this scope token)
        List<Activity> scopeCompleted = dbService.getCompletedActivities(processInstanceId).stream()
            .filter(a -> tokenId.equals(a.getToken()))
            .toList();
        runCompensation(processInstanceId, tokenId, bpmn, scopeCompleted);

        // cancel the transaction scope, then continue from the (interrupting) cancel boundary
        dbService.cancelActiveActivitiesForToken(tokenId);
        dbService.cancelActivity(scopeActivityId);

        BpmnElementModel cancelBoundary = findCancelBoundary(bpmn, transaction.getId());
        if (cancelBoundary != null) {
            proceedToOutgoing(processInstanceId, parentToken, bpmn, cancelBoundary);
        } else {
            log.warn("{}/{}: Transaction {} cancelled but has no cancel boundary", processInstanceId, tokenId, transaction.getId());
        }
    }

    /** Finds the cancel boundary attached to {@code hostId} (a transaction), or null if none. */
    private BpmnElementModel findCancelBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CANCEL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                return element;
            }
        }
        return null;
    }

    /** Finds the compensation boundary attached to {@code hostId}, or null if none. */
    private BpmnElementModel findCompensationBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                return element;
            }
        }
        return null;
    }

    private BpmnElementModel findLinkCatch(BpmnProcessDefinitionModel bpmn, String linkName) {
        if (linkName == null) {
            return null;
        }
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.LINK_CATCH_EVENT) {
                continue;
            }
            String name = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getName)
                .orElse(null);
            if (linkName.equals(name)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Send task: a Camunda-8 send task carries a {@code zeebe:taskDefinition} and runs as a job worker
     * (like a service task); a BPMN-standard send task ({@code messageRef}) is a message throw.
     */
    private void processSendTask(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        boolean jobWorker = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .isPresent();
        if (jobWorker) {
            enterServiceTask(processInstanceId, tokenId, bpmnElement);
        } else {
            processMessageThrow(processInstanceId, tokenId, bpmn, bpmnElement);
        }
    }

    /**
     * Message throw: completes the activity, then delivers the message in-engine by correlating it
     * to any instance waiting on it (so a process can wake another), and continues. Process
     * variables are passed along as the message payload.
     */
    private void processMessageThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String messageName = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getMessageName)
            .orElse(null);

        log.info("{}/{}: Throwing message '{}' at {}: {}/{}", processInstanceId, tokenId, messageName, bpmnElement.getType(), activityId, bpmnElement.getId());

        if (messageName != null) {
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            correlateMessage(messageName, null, variables);
        }

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /**
     * Signal throw: completes the activity, then broadcasts the signal to every active subscriber
     * (1:N, in contrast to a message's 1:1 correlation), and continues. The signal name comes from the
     * resolved event definition.
     */
    private void processSignalThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String signalName = signalName(bpmnElement);
        log.info("{}/{}: Throwing signal '{}' at {}: {}/{}", processInstanceId, tokenId, signalName, bpmnElement.getType(), activityId, bpmnElement.getId());

        if (signalName != null) {
            broadcastSignal(signalName, dbService.getVariables(processInstanceId));
        }

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /**
     * Parks the token at a signal catch event and registers a subscription. A later
     * {@link #broadcastSignal(String, List)} for the same signal resumes the token.
     */
    private void enterSignalCatch(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        String signalName = signalName(bpmnElement);
        if (signalName == null) {
            throw new EngineException("Signal catch event " + bpmnElement.getId() + " has no signal name");
        }
        dbService.createSignalSubscription(processInstanceId, activityId, signalName);
        log.info("{}/{}: Subscribed to signal '{}' at {}: {}/{}", processInstanceId, tokenId, signalName, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    private String signalName(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getName)
            .orElse(null);
    }

    private void enterWaitState(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        log.info("{}/{}: Waiting at {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    /**
     * Parks the token at a timer catch event and schedules a timer job for its due time. The
     * timer scheduler later fires the job and resumes the token via {@link #signal(UUID, List)}.
     */
    private void enterTimerCatch(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        Instant dueAt = computeDueAt(bpmnElement);
        dbService.createTimerJob(activityId, dueAt);
        log.info("{}/{}: Timer scheduled for {} at {}: {}/{}", processInstanceId, tokenId, bpmnElement.getId(), dueAt, activityId, bpmnElement.getType());
    }

    /**
     * Parks the token at a message catch event and registers a subscription. A later
     * {@link #correlateMessage(String, UUID, List)} for the same message resumes the token.
     */
    private void enterMessageCatch(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        String messageName = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getMessageName)
            .orElseThrow(() -> new EngineException("Message catch event " + bpmnElement.getId() + " has no message name"));
        String correlationKey = evaluateCorrelationKey(bpmnElement, processInstanceId);
        dbService.createMessageSubscription(processInstanceId, activityId, messageName, null, correlationKey);
        log.info("{}/{}: Subscribed to message '{}' (key {}) at {}: {}/{}", processInstanceId, tokenId, messageName, correlationKey, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    /**
     * Evaluates a message subscriber's correlation-key FEEL expression (from its {@code zeebe:subscription})
     * against the instance variables, producing the value the message is later matched on. Returns null when
     * the message has no correlation key (name-only correlation).
     */
    private String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getCorrelationKeyExpression)
            .filter(s -> !s.isBlank())
            .orElse(null);
        if (expression == null) {
            return null;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object value = scriptService.evaluateExpression(expression, dbService.getVariables(processInstanceId));
        return value == null ? null : value.toString();
    }

    /**
     * Conditional catch event: if its FEEL condition already holds against the current variables it is a
     * pass-through; otherwise the token parks here (an active activity) until a later variable change
     * re-evaluates the condition to true (see {@link #triggerConditionalEvents}).
     */
    private void enterConditionalCatch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        if (conditionHolds(bpmnElement, variables)) {
            dbService.completeActivity(activityId);
            log.info("{}/{}: Conditional catch {} already true, passing through: {}", processInstanceId, tokenId, bpmnElement.getId(), activityId);
            proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        } else {
            log.info("{}/{}: Waiting on condition at {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
        }
    }

    /** Evaluates a conditional event's FEEL condition against the given variables (a leading {@code =} is stripped). */
    private boolean conditionHolds(BpmnElementModel element, List<ProcessVariable> variables) {
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

    /**
     * Re-evaluates the instance's conditional events after a variable change: any parked conditional catch
     * whose condition is now true is signalled, and any conditional boundary on an active host whose
     * condition is now true is fired. A thread-local guard stops a fired event's own continuation (which
     * runs through {@link #signal}/{@link #fireBoundary}) from recursively re-triggering this pass.
     */
    private void triggerConditionalEvents(UUID processInstanceId) {
        if (Boolean.TRUE.equals(evaluatingConditionals.get())) {
            return;
        }
        evaluatingConditionals.set(true);
        try {
            ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
            if (pi.getCompletedAt() != null) {
                return;
            }
            BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
                BpmnElementModel element = bpmn.getElement(activity.getBpmnElementId());
                if (element == null) {
                    continue;
                }
                if (element.getType() == BpmnElementType.CONDITIONAL_CATCH_EVENT) {
                    if (conditionHolds(element, variables)) {
                        log.info("{}: Conditional catch {} satisfied, firing", processInstanceId, element.getId());
                        signal(activity.getId(), List.of());
                    }
                } else {
                    for (BpmnElementModel boundary : findConditionalBoundaries(bpmn, element.getId())) {
                        if (conditionHolds(boundary, variables)) {
                            log.info("{}: Conditional boundary {} satisfied, firing on host {}", processInstanceId, boundary.getId(), element.getId());
                            fireBoundary(activity.getId(), boundary.getId(), List.of());
                        }
                    }
                }
            }
        } finally {
            evaluatingConditionals.set(false);
        }
    }

    private List<BpmnElementModel> findConditionalBoundaries(BpmnProcessDefinitionModel bpmn, String hostId) {
        List<BpmnElementModel> boundaries = new ArrayList<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CONDITIONAL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                boundaries.add(element);
            }
        }
        return boundaries;
    }

    private Instant computeDueAt(BpmnElementModel element) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId());
    }

    private Instant computeDueAt(TimerEventExtensionModel timer, String elementId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new EngineException("Timer event " + elementId + " has no timer definition");
        }
        return switch (timer.getType()) {
            case DURATION -> Instant.now().plus(Duration.parse(timer.getExpression()));
            case DATE -> Instant.parse(timer.getExpression());
            // timeCycle: first occurrence of an ISO repeating interval (R[n]/<duration>) or a cron expression
            case CYCLE -> com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(timer.getExpression(), Instant.now());
        };
    }

    /**
     * Parks the token at the failing element as an incident instead of propagating the exception
     * (which would roll back the whole process transaction). Marks the element's activity ERROR
     * and records an incident the operator can later resolve via
     * {@link #resolveIncident(UUID, List)}, which re-executes the element.
     */
    private void raiseIncident(UUID processInstanceId, UUID tokenId, BpmnElementModel element, Exception e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        log.error("{}/{}: Incident at {} {}: {}", processInstanceId, tokenId, element.getType(), element.getId(), message, e);

        // the handler creates the element's activity before doing the risky work, so it is visible
        // to this same-transaction query; pick the most recent one for this token + element
        List<Activity> activities = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, element.getId());
        if (activities.isEmpty()) {
            log.error("{}/{}: No activity found for failed element {}, incident not recorded", processInstanceId, tokenId, element.getId());
            return;
        }
        UUID activityId = activities.get(activities.size() - 1).getId();
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }

    @Override
    public void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId) {
        ProcessInstance processInstanceEntity = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstanceEntity.getProcessDefinitionId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);

        execute(processInstanceId, tokenId, bpmn, element);
    }

    private void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element == null) {
            throw new IllegalStateException("Cannot execute a null element — a referenced element was not found in the process definition");
        }
        int depth = executionDepth.get() + 1;
        if (depth > maxExecutionDepth) {
            // Thrown before incrementing the counter: parent frames restore depth via their
            // finally blocks while unwinding, and the outermost frame removes the ThreadLocal.
            throw new EngineException("Execution depth limit (" + maxExecutionDepth + ") exceeded at element '"
                + element.getId() + "' in process instance " + processInstanceId
                + " — likely an unbounded loop or recursive call activity");
        }
        executionDepth.set(depth);
        try {
            BpmnElementType type = element.getType();

            ElementHandler handler = handlers.get(type);
            if (handler == null) {
                // No handler for this element type: park the token as an incident instead of
                // silently dropping it (which would strand the process instance forever). An
                // operator can see the incident and decide how to proceed.
                log.error("{}/{}: Unsupported BpmnElementType {} at element {}, raising incident",
                    processInstanceId, tokenId, type, element.getId());
                UUID activityId = dbService.createActivity(processInstanceId, tokenId, element);
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, "Unsupported BPMN element type: " + type);
                return;
            }
            try {
                handler.handle(processInstanceId, tokenId, bpmn, element);
            } catch (EngineException e) {
                // engine-level aborts (e.g. depth limit) propagate; they are not element failures
                throw e;
            } catch (Exception e) {
                // any element failure (bad FEEL result, variable conversion, service error, ...)
                // parks the token as an incident instead of rolling back the whole process
                raiseIncident(processInstanceId, tokenId, element, e);
            }
        } finally {
            if (depth <= 1) {
                executionDepth.remove();
            } else {
                executionDepth.set(depth - 1);
            }
        }
    }

    private void processCallActivity(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);

        // null-safe: a malformed call activity (no zeebe:calledElement / processId) or an undeployed target
        // becomes an informative incident (a non-EngineException is parked by execute()'s handler) instead of
        // an NPE / NoSuchElementException — the operator can fix the model / deploy the child and retry.
        String key = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getCallActivityExtension)
            .map(ext -> ext.getProcessId())
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalStateException("Call activity '" + bpmnElement.getId() + "' has no zeebe:calledElement processId"));

        Integer version = dbService.getMaxProcessDefinitionVersionByKey(key);
        if (version == null || version == 0) {
            throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' references process '" + key + "' which has no deployed definition");
        }
        ProcessDefinition pd = dbService.getProcessDefinition(key, version);
        UUID processDefinitionId = pd.getId();

        startProcessInstance(activityId, processDefinitionId, variables);
    }

    /**
     * Enters an embedded subprocess: records the subprocess container activity, creates a child
     * token scoped to it, and starts the subprocess's nested start event. When the nested end
     * event is reached (see {@link #processEndEvent}) the container completes and the parent token
     * continues from the subprocess's outgoing flows.
     */
    private void processSubProcess(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        String startEventId = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .map(SubProcessExtensionModel::getStartEventId)
            .orElseThrow(() -> new EngineException("Subprocess " + bpmnElement.getId() + " has no start event"));

        Token childToken = dbService.createToken(tokenId, activityId);
        log.info("{}/{}: Entering {}: {}/{} (scope token {})", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), childToken.getId());

        execute(processInstanceId, childToken.getId(), bpmn, bpmn.getElement(startEventId));
    }

    /**
     * Event-based gateway: a pass-through that arms every outgoing catch event (message/timer/signal)
     * on the same token, letting them race. When the first one fires, {@link #signal} cancels the
     * losing siblings (see {@link #isBehindEventBasedGateway}).
     */
    private void processEventBasedGateway(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{} (arming {} event(s))", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), bpmnElement.getOutgoing().size());

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /** True if {@code element} is a catch event whose (only) incoming flow comes from an event-based gateway. */
    private boolean isBehindEventBasedGateway(BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element.getIncoming() == null) {
            return false;
        }
        for (String incoming : element.getIncoming()) {
            BpmnFlowModel flow = bpmn.getFlow(incoming);
            if (flow == null) {
                continue;
            }
            BpmnElementModel source = bpmn.getElement(flow.getSourceRef());
            if (source != null && source.getType() == BpmnElementType.EVENT_BASED_GATEWAY) {
                return true;
            }
        }
        return false;
    }

    private void processParallelGateway(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            for (String outgoing : outgoings) {
                processFlow(processInstanceId, newTokenId, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                execute(processInstanceId, newTokenId, bpmn, target);
            }
        } else if (incomings.size() > 1) {
            // A join fires only once every incoming flow has arrived. Arrivals are recorded in
            // processFlow and consumed here, so a process that loops back through the same join
            // waits for a fresh set of arrivals instead of re-firing on stale ones.
            Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());
            boolean reached = arrived.containsAll(incomings);

            if (reached) {
                dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
                Token token = dbService.getToken(tokenId);
                UUID oldTokenId = token.getParentId();
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
                for (String outgoing : outgoings) {
                    processFlow(processInstanceId, oldTokenId, outgoing, false, null);
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    String targetRef = flow.getTargetRef();
                    BpmnElementModel target = bpmn.getElement(targetRef);
                    execute(processInstanceId, oldTokenId, bpmn, target);
                }
            } else {
                log.info("{}/{}: Parallel Gateway Not ready yet {}: {}", processInstanceId, tokenId, bpmnElement.getType(), bpmnElement.getId());
            }
        }
    }

    /**
     * Inclusive gateway. Split (1 in, &gt;1 out): activates every outgoing flow whose condition is true
     * — or the default flow when none is — running all activated branches on one shared token (like a
     * parallel split). It records, on the converging inclusive join, how many branches it activated, so
     * the join waits for exactly that many arrivals (the count is dynamic, unlike a parallel join which
     * waits for every incoming flow). Join (&gt;1 in): fires once the activated branches have all arrived.
     *
     * <p>Supports the canonical single-split → single-join diamond (with intermediate tasks/wait states):
     * each activated branch must reach the join through its own incoming flow.
     */
    private void processInclusiveGateway(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (outgoings.size() > 1 && incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);

            String defaultFlowId = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getExclusiveGatewayExtension)
                .map(ExclusiveGatewayExtensionModel::getDefaultFlowId)
                .orElse(null);

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            List<String> activated = new ArrayList<>();
            for (String outgoing : outgoings) {
                if (outgoing.equals(defaultFlowId)) {
                    continue; // the default flow is only taken if nothing else is
                }
                if (isFlowActive(bpmn, outgoing, variables)) {
                    activated.add(outgoing);
                }
            }
            if (activated.isEmpty() && defaultFlowId != null) {
                activated.add(defaultFlowId);
            }

            log.info("{}/{}: Entering and completing {}: {}/{} (activating {} of {} branches)", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), activated.size(), outgoings.size());

            // tell the converging join how many branches to wait for (dynamic for inclusive gateways)
            String joinId = findInclusiveJoin(bpmn, bpmnElement);
            if (joinId != null) {
                dbService.recordInclusiveExpected(processInstanceId, joinId, activated.size());
            }

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            for (String outgoing : activated) {
                processFlow(processInstanceId, newTokenId, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                execute(processInstanceId, newTokenId, bpmn, target);
            }
        } else if (incomings.size() > 1) {
            Integer expected = dbService.getInclusiveExpected(processInstanceId, bpmnElement.getId());
            Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());

            if (expected != null && arrived.size() >= expected) {
                dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
                Token token = dbService.getToken(tokenId);
                UUID oldTokenId = token.getParentId();
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{} (all {} branches arrived)", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), expected);
                for (String outgoing : outgoings) {
                    processFlow(processInstanceId, oldTokenId, outgoing, false, null);
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                    execute(processInstanceId, oldTokenId, bpmn, target);
                }
            } else {
                log.info("{}/{}: Inclusive gateway join not ready yet {}: {} of {} branches arrived", processInstanceId, tokenId, bpmnElement.getId(), arrived.size(), expected);
            }
        } else {
            // 1-in-1-out inclusive gateway: a plain pass-through
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
            proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        }
    }

    /** True if the flow has no condition (always taken) or its FEEL condition evaluates to true. */
    private boolean isFlowActive(BpmnProcessDefinitionModel bpmn, String flowId, List<ProcessVariable> variables) {
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        String expression = Optional.ofNullable(flow)
            .map(BpmnFlowModel::getConditionExpression)
            .map(BpmnConditionExpressionModel::getExpression)
            .filter(str -> !str.isEmpty())
            .map(str -> str.substring(1))
            .orElse(null);
        if (expression == null) {
            return true;
        }
        Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(test);
    }

    /**
     * Forward-searches the graph from the split for the inclusive gateway its branches converge to (an
     * inclusive gateway with more than one incoming flow). Returns {@code null} if none is reachable.
     */
    private String findInclusiveJoin(BpmnProcessDefinitionModel bpmn, BpmnElementModel split) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String outgoing : split.getOutgoing()) {
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            if (flow != null) {
                queue.add(flow.getTargetRef());
            }
        }
        while (!queue.isEmpty()) {
            String elementId = queue.poll();
            if (elementId == null || !visited.add(elementId)) {
                continue;
            }
            BpmnElementModel element = bpmn.getElement(elementId);
            if (element == null) {
                continue;
            }
            if (element.getType() == BpmnElementType.INCLUSIVE_GATEWAY
                    && element.getIncoming() != null && element.getIncoming().size() > 1) {
                return element.getId();
            }
            if (element.getOutgoing() != null) {
                for (String outgoing : element.getOutgoing()) {
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    if (flow != null) {
                        queue.add(flow.getTargetRef());
                    }
                }
            }
        }
        return null;
    }

    private void processExclusiveGateway(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<String> outgoings = bpmnElement.getOutgoing();
        List<String> incoming = bpmnElement.getIncoming();

        if (outgoings.size() > 1 && incoming.size() == 1) {
            // null-safe: a gateway without a <default> attribute has no extensions at all
            String defaultFlowId = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getExclusiveGatewayExtension)
                .map(ExclusiveGatewayExtensionModel::getDefaultFlowId)
                .orElse(null);

            String matchedOutgoing = null;
            for (String outgoing : outgoings) {
                Boolean defaultFlow = Objects.equals(outgoing, defaultFlowId);
                UUID flowActivityId = processFlow(processInstanceId, token, outgoing, true, defaultFlow);
                if (flowActivityId != null) {
                    matchedOutgoing = outgoing;
                    break;
                }
            }

            if (matchedOutgoing == null) {
                if (defaultFlowId == null) {
                    // BPMN: no outgoing condition evaluated true and no default flow is defined. Raise
                    // an incident (not an NPE) so an operator can fix the data and re-run the gateway.
                    throw new IllegalStateException("Exclusive gateway '" + bpmnElement.getId()
                        + "' could not be evaluated: no outgoing sequence flow condition was true and no default flow is defined");
                }
                matchedOutgoing = defaultFlowId;
                processFlow(processInstanceId, token, matchedOutgoing, false, null);
            }

            // routing decided successfully: the gateway is a pass-through, mark it completed
            dbService.completeActivity(activityId);
            BpmnFlowModel flow = bpmn.getFlow(matchedOutgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, token, bpmn, target);
        } else if (outgoings.size() == 1 && incoming.size() > 1) {
            String outgoing = outgoings.get(0);
            processFlow(processInstanceId, token, outgoing, false, null);
            dbService.completeActivity(activityId);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, token, bpmn, target);
        }
    }

    /**
     * Script task: evaluates the inline FEEL script synchronously against the instance variables and, if a
     * result variable is configured, writes the result back before continuing. A script error (bad FEEL,
     * undefined variable, ...) leaves the activity un-completed and surfaces as an incident via the handler
     * dispatch in {@link #execute}, like any other element failure.
     */
    private void processScriptTask(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        // Camunda 8: a script task with a zeebe:taskDefinition runs as a job worker (like a service task)
        boolean jobWorker = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .isPresent();
        if (jobWorker) {
            enterServiceTask(processInstanceId, tokenId, bpmnElement);
            return;
        }

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        ScriptTaskExtensionModel ext = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getScriptTaskExtension)
            .orElseThrow(() -> new IllegalStateException("Script task '" + bpmnElement.getId() + "' has no script"));
        String script = ext.getScript();
        if (script == null || script.isBlank()) {
            throw new IllegalStateException("Script task '" + bpmnElement.getId() + "' has an empty script");
        }

        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        Object result = scriptService.evaluateExpression(script, variables);
        log.info("{}/{}: Script task {}: {}/{} evaluated to {}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), result);

        String resultVariable = ext.getResultVariable();
        if (resultVariable != null && !resultVariable.isBlank()) {
            dbService.setVariables(processInstanceId, List.of(toProcessVariable(resultVariable, result)));
        }

        dbService.completeActivity(activityId);
        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    /**
     * Business rule task: evaluates a DMN decision (by {@code decisionId}, via the DMN engine) or an inline
     * FEEL expression, then writes the result to {@code resultVariable} before continuing. A bad decision /
     * expression surfaces as an incident, like any other element failure.
     */
    private void processBusinessRuleTask(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        BusinessRuleExtensionModel ext = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getBusinessRuleExtension)
            .orElseThrow(() -> new IllegalStateException("Business rule task '" + bpmnElement.getId() + "' has no decision or expression"));

        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        Object result;
        if (ext.getDecisionId() != null && !ext.getDecisionId().isBlank()) {
            result = dmnService.evaluate(ext.getDecisionId(), variables);
        } else if (ext.getExpression() != null && !ext.getExpression().isBlank()) {
            String expression = ext.getExpression().startsWith("=") ? ext.getExpression().substring(1) : ext.getExpression();
            result = scriptService.evaluateExpression(expression, variables);
        } else {
            throw new IllegalStateException("Business rule task '" + bpmnElement.getId() + "' has neither a decision nor an expression");
        }
        log.info("{}/{}: Business rule task {}: {}/{} evaluated to {}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), result);

        if (ext.getResultVariable() != null && !ext.getResultVariable().isBlank()) {
            dbService.setVariables(processInstanceId, List.of(toProcessVariable(ext.getResultVariable(), result)));
        }

        dbService.completeActivity(activityId);
        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /** Maps a FEEL result to a {@link ProcessVariable}, picking the closest of the supported variable types. */
    private ProcessVariable toProcessVariable(String name, Object result) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        if (result instanceof Boolean b) {
            variable.setType(ProcessVariableType.BOOLEAN);
            variable.setValue(b.toString());
        } else if (result instanceof Number number && isIntegral(number)) {
            variable.setType(ProcessVariableType.LONG);
            variable.setValue(Long.toString(number.longValue()));
        } else if (result instanceof Number number) {
            // non-integral number (FEEL returns BigDecimal) -> DOUBLE, stored as a plain decimal string
            java.math.BigDecimal bd = (number instanceof java.math.BigDecimal x)
                ? x : java.math.BigDecimal.valueOf(number.doubleValue());
            variable.setType(ProcessVariableType.DOUBLE);
            variable.setValue(bd.toPlainString());
        } else if (isStructuredResult(result)) {
            // structured result: a Java Map/List (DMN output via FeelEngineApi) or a Scala collection (a FEEL
            // context/list returned by ScriptService) -> normalised to a Java structure and stored as JSON
            variable.setType(ProcessVariableType.JSON);
            variable.setValue(objectMapper.writeValueAsString(toJavaStructure(result)));
        } else {
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(result == null ? "" : result.toString());
        }
        return variable;
    }

    /** Whether a FEEL result is a structured value (object/list) — Java or Scala collection. */
    private boolean isStructuredResult(Object v) {
        return v instanceof java.util.Map || v instanceof java.util.List
            || v instanceof scala.collection.Map || v instanceof scala.collection.Iterable;
    }

    /**
     * Normalises a FEEL result into a JSON-serializable Java structure. FEEL contexts/lists come back from
     * {@code ScriptService} as Scala collections (and from {@code FeelEngineApi} as Java collections); both
     * are converted recursively to {@link java.util.LinkedHashMap}/{@link java.util.ArrayList} with scalar
     * leaves (BigDecimal/Boolean/String) left as-is.
     */
    private Object toJavaStructure(Object v) {
        if (v instanceof scala.collection.Map<?, ?> sm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            scala.collection.Iterator<?> it = sm.iterator();
            while (it.hasNext()) {
                scala.Tuple2<?, ?> entry = (scala.Tuple2<?, ?>) it.next();
                out.put(String.valueOf(entry._1()), toJavaStructure(entry._2()));
            }
            return out;
        }
        if (v instanceof scala.collection.Iterable<?> si) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            scala.collection.Iterator<?> it = si.iterator();
            while (it.hasNext()) {
                out.add(toJavaStructure(it.next()));
            }
            return out;
        }
        if (v instanceof java.util.Map<?, ?> jm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            jm.forEach((k, val) -> out.put(String.valueOf(k), toJavaStructure(val)));
            return out;
        }
        if (v instanceof java.util.List<?> jl) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object e : jl) {
                out.add(toJavaStructure(e));
            }
            return out;
        }
        return v;
    }

    private boolean isIntegral(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return true;
        }
        if (number instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        double d = number.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    private void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        if (isMultiInstance(bpmnElement)) {
            enterMultiInstance(processInstanceId, token, bpmnElement);
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createServiceTask(activityId, serviceTaskRetries(bpmnElement));
        applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }

    /** Retry budget for a service task from {@code zeebe:taskDefinition retries}; default 3 when unset. */
    private int serviceTaskRetries(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ext -> ext.getRetries())
            .orElse(3);
    }

    @Override
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        Activity activity = lockAndReload(serviceTaskId);
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished (redelivered failure, or interrupted by a boundary) — ignore
            log.info("Ignoring failure of service task {} in status {}", serviceTaskId, activity.getStatus());
            return;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Service task failed" : errorMessage;
        // Camunda failJob semantics: an explicit retries value sets the budget (0 -> incident now); otherwise -1
        int remaining;
        if (retries != null) {
            dbService.setServiceTaskRetries(serviceTaskId, retries);
            remaining = retries;
        } else {
            remaining = dbService.decrementServiceTaskRetries(serviceTaskId);
        }
        if (remaining > 0) {
            // retries left: re-dispatch the same job to a worker (the activity stays CREATED)
            log.info("{}/{}: Service task {} failed ({} retries left), re-dispatching: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            return;
        }
        // retries exhausted: park the token and raise an incident carrying the worker's error message
        log.info("{}/{}: Service task {} failed, retries exhausted — raising incident: {}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
        dbService.errorActivity(serviceTaskId);
        dbService.createIncident(serviceTaskId, message);
    }

    /**
     * Applies a task's {@code zeebe:ioMapping} with scoped variables (Camunda 8 semantics). Inputs run on
     * activation and write to the task's **local** scope ({@code activityId}); outputs run on completion and
     * write to the **parent** (process-instance root). Both evaluate against the merged view (root + local),
     * so the local input variables don't leak to the instance — they are dropped when the task completes
     * (see the {@code deleteVariables} call in the complete* methods).
     */
    private void applyIoMappings(UUID processInstanceId, UUID activityId, BpmnElementModel element, boolean inputs) {
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        if (io == null) {
            return;
        }
        List<IoMappingExtensionModel.Mapping> mappings = inputs ? io.getInputs() : io.getOutputs();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId, activityId);
        List<ProcessVariable> results = new ArrayList<>();
        for (IoMappingExtensionModel.Mapping mapping : mappings) {
            if (mapping.getSource() == null || mapping.getTarget() == null || mapping.getTarget().isBlank()) {
                continue;
            }
            String expression = mapping.getSource().startsWith("=") ? mapping.getSource().substring(1) : mapping.getSource();
            Object value = scriptService.evaluateExpression(expression, variables);
            results.add(toProcessVariable(mapping.getTarget(), value));
        }
        if (!results.isEmpty()) {
            // inputs -> local scope (activityId); outputs -> root scope (null)
            dbService.setVariables(processInstanceId, inputs ? activityId : null, results);
            log.info("{}: Applied {} {} mapping(s) at {} (scope {})", processInstanceId, results.size(), inputs ? "input" : "output", element.getId(), inputs ? activityId : "root");
        }
    }

    /**
     * Reads the activity, takes a pessimistic write lock on its process instance, then re-reads the
     * activity under that lock. Serialises all execution touching one instance so concurrent async
     * branches cannot race on joins or double-advance a token; the re-read returns a status that is
     * consistent with the lock (a competing transaction has already committed by the time we hold it).
     */
    private Activity lockAndReload(UUID activityId) {
        Activity activity = dbService.getActivity(activityId);
        dbService.lockProcessInstance(activity.getProcessInstanceId());
        return dbService.getActivity(activityId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(serviceTaskId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete. Ignore anything else to avoid advancing the token twice:
            // a redelivered/late RabbitMQ completion (broker is at-least-once), a boundary-timer
            // interruption (CANCELLED), an already-COMPLETED task, or a task parked on an incident
            // (ERROR) that was superseded by incident-resolve re-execution.
            log.info("Ignoring completion of service task {} in status {}", serviceTaskId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        applyIoMappings(processInstanceId, serviceTaskId, bpmnElement, false);
        aggregateMultiInstanceOutput(processInstanceId, serviceTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, serviceTaskId);
        if (isMultiInstance(bpmnElement) && !multiInstanceContinue(processInstanceId, tokenId, bpmnElement, serviceTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    private void enterUserTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        if (isMultiInstance(bpmnElement)) {
            enterMultiInstance(processInstanceId, token, bpmnElement);
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createUserTask(activityId, extractAssignee(bpmnElement));
        applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        scheduleBoundaryTimers(processInstanceId, activityId, bpmnElement);
        scheduleMessageBoundaries(processInstanceId, activityId, bpmnElement);
        scheduleSignalBoundaries(processInstanceId, activityId, bpmnElement);
    }

    private boolean isMultiInstance(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .isPresent();
    }

    private String extractAssignee(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssignee)
            .orElse(null);
    }

    /**
     * Multi-instance user task. Parallel: spawns all N user-task activities on the same token at once.
     * Sequential: spawns only the first instance; the next is created as each completes (see
     * {@link #multiInstanceContinue}). N comes from {@code loopCardinality}; the join is told to expect N
     * (reusing the parallel/inclusive arrival mechanism).
     *
     * <p>Each instance gets its own scoped {@code inputElement} (the current {@code inputCollection} element)
     * and {@code loopCounter} (1-based), isolated via scoped variables and dropped on completion. Output
     * aggregation ({@code outputCollection}/{@code outputElement}) is not yet supported.
     */
    private void enterMultiInstance(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        MultiInstanceExtensionModel mi = bpmnElement.getExtensions().getMultiInstanceExtension();
        int count = resolveCardinality(processInstanceId, bpmnElement);
        if (count <= 0) {
            log.info("{}/{}: Multi-instance {} has zero instances, skipping", processInstanceId, token, bpmnElement.getId());
            proceedToOutgoing(processInstanceId, token, bpmnElement.getProcessDefinition(), bpmnElement);
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
     * Creates one multi-instance instance: a user-task instance parks for completion; any other (service /
     * job-worker) task creates a service-task job, applies its input mappings (which may reference the
     * per-instance {@code inputElement}) and enqueues it. Per-instance {@code inputElement}/{@code loopCounter}
     * are bound into the instance scope first.
     */
    private void spawnMiInstance(UUID processInstanceId, UUID token, BpmnElementModel element, MultiInstanceExtensionModel mi, Object collection, int index) {
        UUID activityId = dbService.createActivity(processInstanceId, token, element);
        bindMiInstanceVariables(processInstanceId, activityId, mi, collection, index);
        if (element.getType() == BpmnElementType.USER_TASK) {
            dbService.createUserTask(activityId, extractAssignee(element));
        } else {
            dbService.createServiceTask(activityId, serviceTaskRetries(element));
            applyIoMappings(processInstanceId, activityId, element, true);
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
    }

    /**
     * Resolves a multi-instance activity's instance count: from the Camunda 8 {@code zeebe:loopCharacteristics
     * inputCollection} (the collection's size) if present, otherwise from the BPMN {@code loopCardinality}
     * (a literal or FEEL number).
     */
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

    /**
     * Size of a FEEL collection. Handles a {@link java.util.Collection} and, since the FEEL value mapper may
     * return a Scala collection, falls back to its {@code size()} method reflectively (engine-agnostic).
     */
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
                // not a collection-like value
            }
        }
        throw new EngineException("Multi-instance " + elementId + " inputCollection did not evaluate to a list: " + collection);
    }

    /** Evaluates the multi-instance {@code inputCollection} once for per-instance binding, or null when there
     *  is no {@code inputElement} to bind (avoids an unnecessary evaluation). */
    private Object miInputCollection(UUID processInstanceId, MultiInstanceExtensionModel mi) {
        if (mi.getInputElement() == null || mi.getInputElement().isBlank()
            || mi.getInputCollection() == null || mi.getInputCollection().isBlank()) {
            return null;
        }
        return scriptService.evaluateExpression(mi.getInputCollection(), dbService.getVariables(processInstanceId));
    }

    /** Writes a multi-instance instance's per-instance variables into its own (activity) scope: the current
     *  collection element under {@code inputElement} (if configured) and the 1-based {@code loopCounter}.
     *  Scoped variables don't leak to the instance and are dropped when the task completes. */
    private void bindMiInstanceVariables(UUID processInstanceId, UUID scopeId, MultiInstanceExtensionModel mi, Object collection, int index) {
        List<ProcessVariable> locals = new ArrayList<>();
        if (collection != null && mi.getInputElement() != null && !mi.getInputElement().isBlank()) {
            locals.add(toProcessVariable(mi.getInputElement(), collectionElement(collection, index)));
        }
        ProcessVariable loopCounter = new ProcessVariable();
        loopCounter.setName("loopCounter");
        loopCounter.setType(ProcessVariableType.LONG);
        loopCounter.setValue(Long.toString(index + 1L));
        locals.add(loopCounter);
        dbService.setVariables(processInstanceId, scopeId, locals);
    }

    /** Appends a multi-instance instance's {@code outputElement} (a FEEL expression evaluated in the instance
     *  scope) to the {@code outputCollection} (a root JSON list). No-op unless both are configured. */
    private void aggregateMultiInstanceOutput(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
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

    /** Reads the named root variable as a JSON list (or starts a new one), appends {@code value} (normalised
     *  to a Java structure), and writes it back as a JSON variable. */
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
        list.add(toJavaStructure(value));

        ProcessVariable out = new ProcessVariable();
        out.setName(name);
        out.setType(ProcessVariableType.JSON);
        out.setValue(objectMapper.writeValueAsString(list));
        dbService.setVariables(processInstanceId, List.of(out));
    }

    /** Element at {@code index} of a FEEL collection: a {@link java.util.List} (from a JSON list variable) or,
     *  for a Scala collection returned by the FEEL value mapper, its {@code apply(int)} reflectively. */
    private Object collectionElement(Object collection, int index) {
        if (collection instanceof java.util.List<?> list) {
            return index < list.size() ? list.get(index) : null;
        }
        if (collection != null) {
            try {
                return collection.getClass().getMethod("apply", int.class).invoke(collection, index);
            } catch (ReflectiveOperationException ignored) {
                // not an indexable Scala collection
            }
        }
        return null;
    }

    /**
     * Records a multi-instance instance's completion and reports whether the whole multi-instance is done
     * (so the flow should continue). The multi-instance completes when every instance has finished or the
     * {@code completionCondition} holds. For a sequential multi-instance that is not yet done, the next
     * instance is started here. Reuses the parallel/inclusive arrival counter, keyed by the unique activity id.
     */
    private boolean multiInstanceContinue(UUID processInstanceId, UUID token, BpmnElementModel element, UUID completedActivityId) {
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
            // start the next sequential instance on the same token, bound to the next collection element
            spawnMiInstance(processInstanceId, token, element, mi, miInputCollection(processInstanceId, mi), arrived);
            log.info("{}/{}: Multi-instance {} starting next sequential instance ({} of {} done)", processInstanceId, token, miId, arrived, expected);
        } else {
            log.info("{}: Multi-instance {} not ready: {} of {} instances done", processInstanceId, miId, arrived, expected);
        }
        return false;
    }

    /** Evaluates a multi-instance {@code completionCondition} (a FEEL boolean), or false if none is set. */
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

    /**
     * Registers a signal subscription for every signal boundary event attached to the given host
     * activity. When such a signal is later broadcast the boundary fires (see {@link #broadcastSignal}).
     */
    private void scheduleSignalBoundaries(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
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
            String signalName = signalName(element);
            if (signalName == null) {
                throw new EngineException("Signal boundary " + element.getId() + " has no signal name");
            }
            dbService.createSignalSubscription(processInstanceId, hostActivityId, signalName, element.getId());
            log.info("{}: Signal boundary {} subscribed to '{}' on host activity {}", processInstanceId, element.getId(), signalName, hostActivityId);
        }
    }

    /**
     * Registers a message subscription for every message boundary event attached to the given host
     * activity. When such a message is later correlated the boundary fires (see {@link #correlateMessage}).
     */
    private void scheduleMessageBoundaries(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
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
            String correlationKey = evaluateCorrelationKey(element, processInstanceId);
            dbService.createMessageSubscription(processInstanceId, hostActivityId, messageName, element.getId(), correlationKey);
            log.info("{}: Message boundary {} subscribed to '{}' (key {}) on host activity {}", processInstanceId, element.getId(), messageName, correlationKey, hostActivityId);
        }
    }

    /**
     * Schedules interrupting timer boundary jobs for any timer boundary event attached to the
     * given host activity. When such a timer fires before the host completes, the host is cancelled
     * and flow continues from the boundary's outgoing (see {@link #fireBoundaryTimer}).
     */
    private void scheduleBoundaryTimers(UUID processInstanceId, UUID hostActivityId, BpmnElementModel host) {
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
                Instant dueAt = computeDueAt(element);
                dbService.createTimerJob(hostActivityId, dueAt, element.getId());
                log.info("{}: Boundary timer {} scheduled for {} on host activity {}", processInstanceId, element.getId(), dueAt, hostActivityId);
            }
        }
    }

    @Override
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(userTaskId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete — ignore a duplicate/late completion, a boundary-timer
            // interruption (CANCELLED) or a task superseded by incident-resolve (ERROR) to avoid double execution
            log.info("Ignoring completion of user task {} in status {}", userTaskId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, activity.getType(), userTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        applyIoMappings(processInstanceId, userTaskId, bpmnElement, false);
        // multi-instance: append this instance's outputElement to the outputCollection before its scoped
        // variables (inputElement/loopCounter) are dropped
        aggregateMultiInstanceOutput(processInstanceId, userTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, userTaskId);
        if (isMultiInstance(bpmnElement) && !multiInstanceContinue(processInstanceId, token, bpmnElement, userTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        proceedToOutgoing(processInstanceId, token, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    @Override
    public void signal(UUID activityId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(activityId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active waiting element may be resumed — ignore a timer that fired twice, a
            // concurrently-correlated message, or an element superseded by incident-resolve (ERROR)
            log.info("Ignoring signal of {} in status {}", activityId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }
        dbService.completeActivity(activityId);

        log.info("{}/{}: Signalling {}: {}/{}", processInstanceId, tokenId, activity.getType(), activityId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        if (isBehindEventBasedGateway(bpmn, bpmnElement)) {
            // event-based gateway race: this catch won. Cancel the losing sibling catches still parked
            // on this token (the winner is already COMPLETED, so it is not cancelled). Any later trigger
            // for a cancelled sibling is ignored by the status guard above.
            dbService.cancelActiveActivitiesForToken(tokenId);
            log.info("{}/{}: event-based gateway: {} won, losing siblings cancelled", processInstanceId, tokenId, bpmnElement.getId());
        }

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    @Override
    public void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId) {
        fireBoundary(hostActivityId, boundaryElementId, List.of());
    }

    @Override
    public void fireEventSubprocessTimer(UUID processInstanceId, String eventSubprocessId) {
        // the timer job is already marked fired (one-shot), so it never re-fires regardless of interrupting
        triggerEventSubprocess(processInstanceId, eventSubprocessId, List.of());
    }

    /**
     * Fires a boundary event (timer or message) on its host activity. Interrupting boundaries cancel
     * the host and continue the host's token from the boundary; non-interrupting ones leave the host
     * running and spawn a parallel branch on a new token. A no-op if the host already finished.
     */
    private void fireBoundary(UUID hostActivityId, String boundaryElementId, List<ProcessVariable> variables) {
        Activity host = lockAndReload(hostActivityId);
        if (host.getStatus() == ActivityStatus.COMPLETED || host.getStatus() == ActivityStatus.CANCELLED) {
            // host already finished before the boundary fired
            log.info("Boundary {} fired but host activity {} is {}, ignoring", boundaryElementId, hostActivityId, host.getStatus());
            return;
        }

        UUID processInstanceId = host.getProcessInstanceId();
        UUID tokenId = host.getToken();

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel boundary = bpmn.getElement(boundaryElementId);

        boolean interrupting = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);

        if (interrupting) {
            dbService.cancelActivity(hostActivityId);
            log.info("{}/{}: Boundary {} interrupting host {}", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId());
            proceedToOutgoing(processInstanceId, tokenId, bpmn, boundary);
        } else {
            // non-interrupting: the host keeps running; the boundary spawns a parallel branch on a
            // new token (child of the host's token)
            Token branch = dbService.createToken(tokenId);
            log.info("{}/{}: Boundary {} firing non-interrupting on host {} (branch token {})", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId(), branch.getId());
            proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary);
            rearmRepeatingBoundaryTimer(hostActivityId, boundary);
        }
        triggerConditionalEvents(processInstanceId);
    }

    /**
     * A repeating ({@code timeCycle} unbounded {@code R/<duration>} or cron) non-interrupting boundary timer
     * re-arms its next occurrence after firing, so it keeps firing while the host activity is active (a
     * "remind every N" pattern). When the host completes, the next firing finds it finished and is ignored.
     */
    private void rearmRepeatingBoundaryTimer(UUID hostActivityId, BpmnElementModel boundary) {
        if (boundary.getType() != BpmnElementType.BOUNDARY_TIMER_EVENT) {
            return;
        }
        TimerEventExtensionModel timer = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null);
        if (timer == null || timer.getType() != com.zorrodev.bpm.engine.bpmn.model.TimerEventType.CYCLE
            || !com.zorrodev.bpm.engine.scheduler.TimerExpressions.isInfiniteCycle(timer.getExpression())) {
            return;
        }
        dbService.createTimerJob(hostActivityId, computeDueAt(boundary), boundary.getId());
    }

    @Override
    public void correlateMessage(String messageName, UUID processInstanceId, List<ProcessVariable> variables) {
        correlateMessage(messageName, null, processInstanceId, variables);
    }

    /**
     * Correlates a message, optionally by a correlation-key value. When {@code correlationKey} is given,
     * only subscriptions whose stored key matches are woken (targeted delivery — this disambiguates
     * multiple instances waiting on the same message name); otherwise the message correlates by name
     * (optionally narrowed to {@code processInstanceId}).
     */
    @Override
    public void correlateMessage(String messageName, String correlationKey, UUID processInstanceId, List<ProcessVariable> variables) {
        List<MessageSubscription> subscriptions;
        if (correlationKey != null) {
            subscriptions = dbService.findMessageSubscriptionsByKey(messageName, correlationKey);
            if (processInstanceId != null) {
                subscriptions = subscriptions.stream()
                    .filter(s -> processInstanceId.equals(s.getProcessInstanceId()))
                    .toList();
            }
        } else {
            subscriptions = dbService.findMessageSubscriptions(messageName, processInstanceId);
        }

        // untargeted correlation by name (no instance, no key) may also start new instances via message starts
        List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> startSubscriptions =
            (processInstanceId == null && correlationKey == null) ? dbService.findMessageStartSubscriptions(messageName) : List.of();

        if (subscriptions.isEmpty() && startSubscriptions.isEmpty()) {
            log.info("No active subscription for message '{}' (instance {}, key {})", messageName, processInstanceId, correlationKey);
            return;
        }

        for (com.zorrodev.bpm.engine.dto.MessageStartSubscription start : startSubscriptions) {
            log.info("Message '{}' starting a new instance of {} at {}", messageName, start.getProcessDefinitionId(), start.getElementId());
            startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables);
        }

        for (MessageSubscription subscription : subscriptions) {
            if (subscription.getEventSubprocessId() != null) {
                // message-started event sub-process: start the handler within the subscribed instance. The
                // subscription is consumed only for interrupting handlers (a non-interrupting one keeps
                // listening and can fire again) — triggerEventSubprocess decides.
                log.info("Correlating message '{}' to event sub-process {} on instance {}", messageName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                boolean interrupting = triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables);
                if (interrupting) {
                    dbService.consumeMessageSubscription(subscription.getId());
                }
                continue;
            }
            dbService.consumeMessageSubscription(subscription.getId());
            if (subscription.getBoundaryElementId() != null) {
                // message boundary: fire the boundary (interrupt/non-interrupt the host)
                log.info("Correlating message '{}' to boundary {} on instance {} activity {}", messageName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables);
            } else {
                // message catch: signal the waiting activity
                log.info("Correlating message '{}' to instance {} activity {}", messageName, subscription.getProcessInstanceId(), subscription.getActivityId());
                signal(subscription.getActivityId(), variables);
            }
        }
    }

    /**
     * Starts a message-triggered event sub-process within {@code processInstanceId}. An interrupting event
     * sub-process consumes the subscription, cancels the instance's active activities (the main flow) and
     * runs the handler on a fresh token so its end event completes the instance. A non-interrupting one
     * keeps the subscription and the main flow, running the handler in its own subprocess scope (its end
     * completes only the scope). A no-op if the instance has already completed.
     *
     * <p>Scope: top-level, message-triggered event sub-processes. Non-message triggers and event
     * sub-processes nested inside an embedded subprocess are not yet supported.
     */
    private boolean triggerEventSubprocess(UUID processInstanceId, String eventSubprocessId, List<ProcessVariable> variables) {
        dbService.lockProcessInstance(processInstanceId);
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        if (pi.getCompletedAt() != null) {
            log.info("{}: Event sub-process {} trigger ignored, instance already completed", processInstanceId, eventSubprocessId);
            return false;
        }
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        BpmnElementModel eventSubProcess = bpmn.getElement(eventSubprocessId);
        SubProcessExtensionModel ext = Optional.ofNullable(eventSubProcess.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .orElseThrow(() -> new EngineException("Event sub-process " + eventSubprocessId + " has no metadata"));

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        if (ext.isInterrupting()) {
            // interrupting: cancel the main flow, then run the handler on a fresh (non-scope) token so its
            // end event completes the whole instance. The caller consumes the triggering subscription.
            dbService.cancelActiveActivities(processInstanceId);
            Token token = dbService.createToken(null);
            log.info("{}/{}: Interrupting event sub-process {} starting at {}", processInstanceId, token.getId(), eventSubprocessId, ext.getStartEventId());
            execute(processInstanceId, token.getId(), ext.getStartEventId());
            return true;
        }
        // non-interrupting: leave the subscription (it can fire again) and the main flow alone; run the
        // handler in its own subprocess scope so its end completes only the scope (see finishBranch),
        // not the instance
        Token branchToken = dbService.createToken(null);
        UUID containerActivityId = dbService.createActivity(processInstanceId, branchToken.getId(), eventSubProcess);
        Token scopeToken = dbService.createToken(branchToken.getId(), containerActivityId);
        log.info("{}/{}: Non-interrupting event sub-process {} starting at {} (scope {})", processInstanceId, scopeToken.getId(), eventSubprocessId, ext.getStartEventId(), containerActivityId);
        execute(processInstanceId, scopeToken.getId(), ext.getStartEventId());
        return false;
    }

    /**
     * Broadcasts a signal: wakes every active subscription with the matching name, across process
     * instances (1:N). Each waiting activity is signalled under its own per-instance lock (via
     * {@link #signal}), so concurrent broadcasts/correlations stay isolated per instance.
     */
    private void broadcastSignal(String signalName, List<ProcessVariable> variables) {
        List<SignalSubscription> subscriptions = dbService.findSignalSubscriptions(signalName);
        List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> startSubscriptions =
            dbService.findSignalStartSubscriptions(signalName);

        if (subscriptions.isEmpty() && startSubscriptions.isEmpty()) {
            log.info("No active subscription for signal '{}'", signalName);
            return;
        }

        // signal start: every subscribed definition starts a fresh instance (broadcast)
        for (com.zorrodev.bpm.engine.dto.SignalStartSubscription start : startSubscriptions) {
            log.info("Signal '{}' starting a new instance of {} at {}", signalName, start.getProcessDefinitionId(), start.getElementId());
            startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables);
        }

        for (SignalSubscription subscription : subscriptions) {
            if (subscription.getEventSubprocessId() != null) {
                // signal-started event sub-process: consume only for interrupting handlers (it can re-fire otherwise)
                log.info("Broadcasting signal '{}' to event sub-process {} on instance {}", signalName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                boolean interrupting = triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables);
                if (interrupting) {
                    dbService.consumeSignalSubscription(subscription.getId());
                }
                continue;
            }
            dbService.consumeSignalSubscription(subscription.getId());
            if (subscription.getBoundaryElementId() != null) {
                // signal boundary: fire the boundary (interrupt/non-interrupt the host)
                log.info("Broadcasting signal '{}' to boundary {} on instance {} activity {}", signalName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables);
            } else {
                // signal catch: signal the waiting activity
                log.info("Broadcasting signal '{}' to instance {} activity {}", signalName, subscription.getProcessInstanceId(), subscription.getActivityId());
                signal(subscription.getActivityId(), variables);
            }
        }
    }

    @Override
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables) {
        Incident incident = dbService.getIncident(incidentId);
        Activity activity = dbService.getActivity(incident.getActivityId());
        dbService.lockProcessInstance(activity.getProcessInstanceId());

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(activity.getProcessInstanceId(), variables);
        }
        dbService.completeIncident(incidentId);

        // Cancel the parked (ERROR) activity before re-executing: re-execution creates a fresh active
        // activity, and cancelling the old one ensures a late/duplicate worker completion of its in-flight
        // job is ignored (completeServiceTask only acts on active tasks) instead of advancing the token again.
        dbService.cancelActivity(incident.getActivityId());

        log.info("{}/{}: Resolving incident {} at {}: re-executing", activity.getProcessInstanceId(), activity.getToken(), incidentId, activity.getBpmnElementId());
        execute(activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId());
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        if (bpmn.getStartEvent() == null) {
            throw new EngineException("Process definition " + processDefinitionId
                + " has no plain start event; it can only be started by a message or timer start event");
        }
        return startProcessInstanceAt(parentActivityId, processDefinitionId, bpmn.getStartEvent().getId(), variables);
    }

    @Override
    public UUID startProcessInstanceFromStartEvent(UUID processDefinitionId, String startElementId, List<ProcessVariable> variables) {
        return startProcessInstanceAt(null, processDefinitionId, startElementId, variables);
    }

    /**
     * Starts a process instance beginning at a specific start element (used by message/timer start
     * events, which begin at their own start node rather than the plain start).
     */
    private UUID startProcessInstanceAt(UUID parentActivityId, UUID processDefinitionId, String startEventId, List<ProcessVariable> variables) {
        UUID processInstanceId = dbService.createProcessInstance(parentActivityId, processDefinitionId, variables);

        dbService.setVariables(processInstanceId, variables);

        UUID parentTokenId = null;
        if (parentActivityId != null) {
            Activity activity = dbService.getActivity(parentActivityId);
            Token token = dbService.getToken(activity.getToken());
            parentTokenId = token.getId();
        }
        Token token = dbService.createToken(parentTokenId);

        // register triggers for event sub-processes before the main flow runs, so a message arriving
        // while the instance is active can start the handler
        subscribeEventSubprocesses(processInstanceId, processDefinitionId);

        execute(processInstanceId, token.getId(), startEventId);

        return processInstanceId;
    }

    /**
     * Registers an instance-scoped message subscription for every message-triggered event sub-process in
     * the definition. When such a message is later correlated to this instance the event sub-process is
     * started (see {@link #triggerEventSubprocess}). Non-message triggers are not yet supported.
     */
    private void subscribeEventSubprocesses(UUID processInstanceId, UUID processDefinitionId) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null) {
                continue;
            }
            if (ext.getTriggerMessageName() != null) {
                dbService.createEventSubprocessMessageSubscription(processInstanceId, ext.getTriggerMessageName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to message '{}'", processInstanceId, element.getId(), ext.getTriggerMessageName());
            } else if (ext.getTriggerSignalName() != null) {
                dbService.createEventSubprocessSignalSubscription(processInstanceId, ext.getTriggerSignalName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to signal '{}'", processInstanceId, element.getId(), ext.getTriggerSignalName());
            } else if (ext.getTriggerTimer() != null) {
                Instant dueAt = computeDueAt(ext.getTriggerTimer(), element.getId());
                dbService.createEventSubprocessTimerJob(processInstanceId, dueAt, element.getId());
                log.info("{}: Event sub-process {} scheduled timer for {}", processInstanceId, element.getId(), dueAt);
            }
            // error-triggered event sub-processes need no subscription: they fire via throwError propagation
        }
    }

    private UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstance.getProcessDefinitionId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        if (flow == null) {
            throw new IllegalStateException("Sequence flow '" + flowId + "' not found in the process definition");
        }
        String targetRef = flow.getTargetRef();
        String sourceRef = flow.getSourceRef();
        BpmnElementModel target = bpmn.getElement(targetRef);
        BpmnElementModel source = bpmn.getElement(sourceRef);
        if (target == null) {
            throw new IllegalStateException("Target element '" + targetRef + "' of sequence flow '" + flowId + "' not found in the process definition");
        }

        UUID flowActivityId = null;

        if (processExpression) {
            String expression = Optional.ofNullable(flow)
                .map(BpmnFlowModel::getConditionExpression)
                .map(BpmnConditionExpressionModel::getExpression)
                .filter(str -> !str.isEmpty())
                .map(str -> str.substring(1))
                .orElse(null);
            if (!(Objects.isNull(expression) && defaultFlow)) {
                List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
                Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
                if (Boolean.TRUE.equals(test)) {
                    flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
                }
            }
        } else {
            flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
        }

        if (flowActivityId != null) {
            dbService.completeActivity(flowActivityId);
            log.info("{}/{}: Flow: {}/{} => from {}/{} to {}/{}", processInstanceId, tokenId, flowActivityId, flowId, source.getType(), source.getId(), target.getType(), target.getId());

            // Arriving at a parallel- or inclusive-gateway join: record this incoming flow so the join
            // can tell when every (activated) branch has arrived. Recorded only for flows actually
            // taken (conditional flows that evaluate false never reach here).
            if ((target.getType() == BpmnElementType.PARALLEL_GATEWAY || target.getType() == BpmnElementType.INCLUSIVE_GATEWAY)
                    && target.getIncoming().size() > 1) {
                dbService.recordParallelGatewayArrival(processInstanceId, target.getId(), flowId);
            }
        }

        return flowActivityId;
    }

    private void processEndEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        finishBranch(processInstanceId, tokenId, bpmn);
    }

    /**
     * Ends the current branch at an end event: if the token is inside an embedded subprocess scope,
     * completes the container and continues the parent token from the subprocess's outgoing flows;
     * otherwise completes the process instance and continues the parent call activity (if any). Shared
     * by plain and escalation end events.
     */
    private void finishBranch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn) {
        Token endToken = dbService.getToken(tokenId);
        if (endToken != null && endToken.getScopeActivityId() != null) {
            // end of an embedded subprocess scope: complete the container and continue the parent
            // token from the subprocess's outgoing flows; the process instance stays running
            UUID subProcessActivityId = endToken.getScopeActivityId();
            dbService.completeActivity(subProcessActivityId);
            Activity subProcessActivity = dbService.getActivity(subProcessActivityId);
            BpmnElementModel subProcessElement = bpmn.getElement(subProcessActivity.getBpmnElementId());
            UUID parentTokenId = endToken.getParentId();
            log.info("{}/{}: Completing {}: {}/{}", processInstanceId, parentTokenId, subProcessElement.getType(), subProcessActivityId, subProcessElement.getId());
            proceedToOutgoing(processInstanceId, parentTokenId, bpmn, subProcessElement);
            return;
        }

        dbService.completeProcessInstance(processInstanceId);

        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        UUID parentActivityId = pi.getParentActivityId();
        if (parentActivityId != null) {
            // a call activity finished: continuation mutates the *parent* instance, so lock it
            // (consistent child→parent ordering keeps this deadlock-free) before advancing it
            Activity parentActivity = dbService.getActivity(parentActivityId);
            dbService.lockProcessInstance(parentActivity.getProcessInstanceId());
            dbService.completeActivity(parentActivityId);

            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentActivity.getProcessInstanceId());
            UUID parentProcessDefinitionId = parentProcessInstance.getProcessDefinitionId();
            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessDefinitionId);
            BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
            if (parentBpmnElement == null) {
                throw new IllegalStateException("Call activity element '" + parentActivity.getBpmnElementId() + "' not found in the parent process definition");
            }

            // Camunda 8: propagateAllChildVariables (default true) copies the child's variables up to the
            // parent; when explicitly false, the child's variables are not propagated.
            boolean propagate = Optional.ofNullable(parentBpmnElement)
                .map(BpmnElementModel::getExtensions)
                .map(BpmnElementExtensionModel::getCallActivityExtension)
                .map(ext -> ext.getPropagateAllChildVariables())
                .orElse(Boolean.TRUE);
            if (propagate) {
                dbService.setVariables(parentProcessInstanceId, dbService.getVariables(processInstanceId));
            }

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            proceedToOutgoing(parentProcessInstanceId, parentToken, parentBpmn, parentBpmnElement);
        }
    }

    /**
     * Terminate end event: ends the whole process instance immediately, cancelling any other
     * active activities (parked user tasks, waiting catch events, parallel branches).
     */
    private void processTerminateEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Terminating instance at {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        dbService.cancelActiveActivities(processInstanceId);
        dbService.completeProcessInstance(processInstanceId);
    }

    /**
     * Error end event: completes its activity, then throws a BPMN error that propagates up the scope
     * hierarchy looking for a matching error boundary (see {@link #throwError}). If nothing catches it
     * the error is recorded as an incident on this element instead of silently ending the instance.
     */
    private void processErrorEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String errorCode = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getCode)
            .orElse(null);

        log.info("{}/{}: Error end {} thrown (code={}) at {}", processInstanceId, tokenId, bpmnElement.getId(), errorCode, activityId);

        boolean handled = throwError(processInstanceId, tokenId, errorCode);
        if (!handled) {
            dbService.errorActivity(activityId);
            dbService.createIncident(activityId, "Unhandled BPMN error" + (errorCode != null ? " '" + errorCode + "'" : ""));
        }
    }

    /**
     * Propagates a BPMN error from {@code tokenId} outward through the scope hierarchy, looking for
     * an interrupting error boundary that matches {@code errorCode} (a boundary without a code is a
     * catch-all). Search order: enclosing embedded subprocess scopes (innermost first), then — if the
     * instance is a called process — the call activity in the parent instance, recursively. When a
     * handler is found the interrupted scope is cancelled and flow continues from the boundary.
     *
     * @return {@code true} if an error boundary handled the error, {@code false} if it escaped unhandled.
     */
    private boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findErrorBoundary(bpmn, scope.getBpmnElementId(), errorCode);
            if (boundary != null) {
                dbService.cancelActiveActivitiesForToken(tok.getId());
                dbService.cancelActivity(scope.getId());
                log.info("{}: error '{}' caught by boundary {} on subprocess {}", processInstanceId, errorCode, boundary.getId(), scope.getBpmnElementId());
                proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary);
                return true;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // a top-level error-triggered event sub-process handles the error here (interrupting the main flow)
        BpmnElementModel errorHandler = findEventSubprocessErrorHandler(bpmn, errorCode);
        if (errorHandler != null) {
            log.info("{}: error '{}' caught by event sub-process {}", processInstanceId, errorCode, errorHandler.getId());
            triggerEventSubprocess(processInstanceId, errorHandler.getId(), List.of());
            return true;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findErrorBoundary(parentBpmn, callActivity.getBpmnElementId(), errorCode);

            // the error escapes this child instance regardless of whether the parent catches it
            dbService.cancelActiveActivities(processInstanceId);
            dbService.completeProcessInstance(processInstanceId);

            if (boundary != null) {
                dbService.cancelActivity(callActivity.getId());
                log.info("{}: error '{}' caught by boundary {} on call activity {}", parentInstanceId, errorCode, boundary.getId(), callActivity.getBpmnElementId());
                proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary);
                return true;
            }
            // not caught on the call activity: keep propagating within the parent instance
            return throwError(parentInstanceId, callActivity.getToken(), errorCode);
        }

        return false;
    }

    /**
     * Finds an interrupting error boundary attached to {@code attachedToRef} whose error code matches
     * {@code errorCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     */
    private BpmnElementModel findErrorBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String errorCode) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ERROR_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode == null || boundaryCode.equals(errorCode)) {
                return element;
            }
        }
        return null;
    }

    /** Finds a top-level error-triggered event sub-process matching {@code errorCode} (null code = catch-all). */
    private BpmnElementModel findEventSubprocessErrorHandler(BpmnProcessDefinitionModel bpmn, String errorCode) {
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null || !ext.isErrorTriggered()) {
                continue;
            }
            if (ext.getTriggerErrorCode() == null || ext.getTriggerErrorCode().equals(errorCode)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Escalation end event: completes its activity, raises a (non-critical) escalation that propagates
     * up the scope hierarchy looking for an escalation boundary, then ends the branch like a plain end
     * event. An uncaught escalation is non-critical — it does not create an incident.
     */
    private void processEscalationEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String escalationCode = escalationCode(bpmnElement);
        log.info("{}/{}: Escalation end {} thrown (code={}) at {}", processInstanceId, tokenId, bpmnElement.getId(), escalationCode, activityId);

        boolean interrupted = throwEscalation(processInstanceId, tokenId, escalationCode);
        // if an interrupting boundary cancelled this token's scope, the branch is already gone
        if (!interrupted) {
            finishBranch(processInstanceId, tokenId, bpmn);
        }
    }

    /**
     * Escalation throw event: completes its activity, raises a (non-critical) escalation, then continues
     * down its own outgoing flow regardless of whether the escalation was caught (escalation, unlike a
     * thrown error, never interrupts the throwing path).
     */
    private void processEscalationThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String escalationCode = escalationCode(bpmnElement);
        log.info("{}/{}: Escalation throw {} (code={}) at {}", processInstanceId, tokenId, bpmnElement.getId(), escalationCode, activityId);

        boolean interrupted = throwEscalation(processInstanceId, tokenId, escalationCode);
        // escalation never interrupts the throwing path unless an interrupting boundary on an enclosing
        // scope of this very token fired (cancelling it); otherwise continue down the outgoing flow
        if (!interrupted) {
            proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        }
    }

    private String escalationCode(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getCode)
            .orElse(null);
    }

    /**
     * Propagates an escalation from {@code tokenId} outward through the scope hierarchy, looking for an
     * escalation boundary that matches {@code escalationCode} (a boundary without a code is a catch-all).
     * Mirrors {@link #throwError} but supports non-interrupting boundaries (the host scope keeps running
     * and a parallel branch is spawned) and never cancels the instance or raises an incident, because an
     * escalation is non-critical.
     *
     * @return {@code true} if an interrupting boundary on a scope enclosing {@code tokenId} fired
     *         (so the throwing path was cancelled and must not continue), {@code false} otherwise.
     */
    private boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findEscalationBoundary(bpmn, scope.getBpmnElementId(), escalationCode);
            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    dbService.cancelActiveActivitiesForToken(tok.getId());
                    dbService.cancelActivity(scope.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on subprocess {}", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId());
                    proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary);
                    return true;
                }
                Token branch = dbService.createToken(tok.getParentId());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on subprocess {} (branch token {})", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId(), branch.getId());
                proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary);
                return false;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findEscalationBoundary(parentBpmn, callActivity.getBpmnElementId(), escalationCode);

            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    // interrupting escalation on a call activity cancels the child instance
                    dbService.cancelActiveActivities(processInstanceId);
                    dbService.completeProcessInstance(processInstanceId);
                    dbService.cancelActivity(callActivity.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on call activity {}", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId());
                    proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary);
                    return true; // child instance was cancelled, so the throwing path is gone
                }
                // the child instance keeps running; a parallel branch is spawned in the parent
                Token branch = dbService.createToken(callActivity.getToken());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on call activity {} (branch token {})", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId(), branch.getId());
                proceedToOutgoing(parentInstanceId, branch.getId(), parentBpmn, boundary);
                return false;
            }
            // not caught on the call activity: keep propagating within the parent instance for handling,
            // but the child's throwing path is not interrupted by it
            throwEscalation(parentInstanceId, callActivity.getToken(), escalationCode);
            return false;
        }

        log.info("{}: escalation '{}' escaped unhandled (non-critical, ignored)", processInstanceId, escalationCode);
        return false;
    }

    private boolean isInterrupting(BpmnElementModel boundary) {
        return Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);
    }

    /**
     * Finds an escalation boundary attached to {@code attachedToRef} whose escalation code matches
     * {@code escalationCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     */
    private BpmnElementModel findEscalationBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String escalationCode) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ESCALATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode == null || boundaryCode.equals(escalationCode)) {
                return element;
            }
        }
        return null;
    }

    private void processStartEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

}
