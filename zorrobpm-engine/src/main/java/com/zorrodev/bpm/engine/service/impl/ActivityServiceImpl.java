package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;

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

    private final Map<BpmnElementType, ElementHandler> handlers = createHandlers();

    private Map<BpmnElementType, ElementHandler> createHandlers() {
        Map<BpmnElementType, ElementHandler> map = new EnumMap<>(BpmnElementType.class);
        map.put(BpmnElementType.START_EVENT, (pi, t, bpmn, el) -> processStartEvent(pi, t, bpmn, el));
        map.put(BpmnElementType.END_EVENT, this::processEndEvent);
        map.put(BpmnElementType.TERMINATE_END_EVENT, this::processTerminateEnd);
        map.put(BpmnElementType.SERVICE_TASK, (pi, t, bpmn, el) -> enterServiceTask(pi, t, el));
        map.put(BpmnElementType.USER_TASK, (pi, t, bpmn, el) -> enterUserTask(pi, t, el));
        map.put(BpmnElementType.EXCLUSIVE_GATEWAY, this::processExclusiveGateway);
        map.put(BpmnElementType.PARALLEL_GATEWAY, this::processParallelGateway);
        map.put(BpmnElementType.CALL_ACTIVITY, this::processCallActivity);
        map.put(BpmnElementType.SUB_PROCESS, this::processSubProcess);
        // Catch events are wait states: the token parks here until an external trigger
        // (timer fires / message correlated) resumes it via signal(...). Until the timer
        // and message subsystems land, these elements at least park cleanly with an active
        // activity instead of silently falling through to "Unsupported".
        map.put(BpmnElementType.INTERMEDIATE_CATCH_EVENT, (pi, t, bpmn, el) -> enterWaitState(pi, t, el));
        map.put(BpmnElementType.MESSAGE_CATCH_EVENT, (pi, t, bpmn, el) -> enterMessageCatch(pi, t, el));
        map.put(BpmnElementType.TIMER_CATCH_EVENT, (pi, t, bpmn, el) -> enterTimerCatch(pi, t, el));
        // Throw events are pass-through: a plain intermediate throw has no side effect and simply
        // continues. (Message throw publishing is added with the message subsystem.)
        map.put(BpmnElementType.INTERMEDIATE_THROW_EVENT, this::processThrowEvent);
        map.put(BpmnElementType.MESSAGE_THROW_EVENT, this::processMessageThrow);
        return map;
    }

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     */
    private void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        for (String outgoing : element.getOutgoing()) {
            processFlow(processInstanceId, tokenId, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
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
        dbService.createMessageSubscription(processInstanceId, activityId, messageName);
        log.info("{}/{}: Subscribed to message '{}' at {}: {}/{}", processInstanceId, tokenId, messageName, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    private Instant computeDueAt(BpmnElementModel element) {
        TimerEventExtensionModel timer = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null);
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new EngineException("Timer event " + element.getId() + " has no timer definition");
        }
        return switch (timer.getType()) {
            case DURATION -> Instant.now().plus(Duration.parse(timer.getExpression()));
            case DATE -> Instant.parse(timer.getExpression());
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
                log.info("Unsupported BpmnElementType: " + type);
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

        String key = bpmnElement.getExtensions().getCallActivityExtension().getProcessId();
        Integer version = dbService.getMaxProcessDefinitionVersionByKey(key);
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

    private void processParallelGateway(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
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
            boolean reached = true;

            for (String incoming : incomings) {
                List<Activity> activities = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, incoming);
                if (activities.isEmpty()) {
                    reached = false;
                    break;
                }
            }

            if (reached) {
                Token token = dbService.getToken(tokenId);
                UUID oldTokenId = token.getParentId();
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
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

    private void processExclusiveGateway(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<String> outgoings = bpmnElement.getOutgoing();
        List<String> incoming = bpmnElement.getIncoming();

        if (outgoings.size() > 1 && incoming.size() == 1) {
            String matchedOutgoing = null;
            for (String outgoing : outgoings) {
                Boolean defaultFlow = Objects.equals(outgoing, Optional.ofNullable(bpmnElement).map(BpmnElementModel::getExtensions).map(BpmnElementExtensionModel::getExclusiveGatewayExtension).map(ExclusiveGatewayExtensionModel::getDefaultFlowId).orElse(null));
                UUID flowActivityId = processFlow(processInstanceId, token, outgoing, true, defaultFlow);
                if (flowActivityId != null) {
                    matchedOutgoing = outgoing;
                    break;
                }
            }

            if (matchedOutgoing != null) {
                BpmnFlowModel flow = bpmn.getFlow(matchedOutgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                execute(processInstanceId, token, bpmn, target);
            } else {
                String outgoing = bpmnElement.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId();
                processFlow(processInstanceId, token, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                execute(processInstanceId, token, bpmn, target);
            }
        } else if (outgoings.size() == 1 && incoming.size() > 1) {
            String outgoing = outgoings.get(0);
            processFlow(processInstanceId, token, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, token, bpmn, target);
        }
    }

    private void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createServiceTask(activityId);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        Activity activity = dbService.getActivity(serviceTaskId);
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    private void enterUserTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createUserTask(activityId);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        scheduleBoundaryTimers(processInstanceId, activityId, bpmnElement);
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
        Activity activity = dbService.getActivity(userTaskId);
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished, e.g. interrupted by a boundary timer — avoid double execution
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

        proceedToOutgoing(processInstanceId, token, bpmn, bpmnElement);
    }

    @Override
    public void signal(UUID activityId, List<ProcessVariable> variables) {
        Activity activity = dbService.getActivity(activityId);
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

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    @Override
    public void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId) {
        Activity host = dbService.getActivity(hostActivityId);
        if (host.getStatus() == ActivityStatus.COMPLETED || host.getStatus() == ActivityStatus.CANCELLED) {
            // host already finished before the timer fired: interrupting boundary is a no-op
            log.info("Boundary timer {} fired but host activity {} is {}, ignoring", boundaryElementId, hostActivityId, host.getStatus());
            return;
        }

        UUID processInstanceId = host.getProcessInstanceId();
        UUID tokenId = host.getToken();
        dbService.cancelActivity(hostActivityId);

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel boundary = bpmn.getElement(boundaryElementId);

        log.info("{}/{}: Boundary timer {} interrupting host {}", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId());
        proceedToOutgoing(processInstanceId, tokenId, bpmn, boundary);
    }

    @Override
    public void correlateMessage(String messageName, UUID processInstanceId, List<ProcessVariable> variables) {
        List<MessageSubscription> subscriptions = dbService.findMessageSubscriptions(messageName, processInstanceId);
        if (subscriptions.isEmpty()) {
            log.info("No active subscription for message '{}' (instance {})", messageName, processInstanceId);
            return;
        }
        for (MessageSubscription subscription : subscriptions) {
            dbService.consumeMessageSubscription(subscription.getId());
            log.info("Correlating message '{}' to instance {} activity {}", messageName, subscription.getProcessInstanceId(), subscription.getActivityId());
            signal(subscription.getActivityId(), variables);
        }
    }

    @Override
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables) {
        Incident incident = dbService.getIncident(incidentId);
        Activity activity = dbService.getActivity(incident.getActivityId());

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(activity.getProcessInstanceId(), variables);
        }
        dbService.completeIncident(incidentId);

        log.info("{}/{}: Resolving incident {} at {}: re-executing", activity.getProcessInstanceId(), activity.getToken(), incidentId, activity.getBpmnElementId());
        execute(activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId());
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        UUID processInstanceId = dbService.createProcessInstance(parentActivityId, processDefinitionId, variables);

        dbService.setVariables(processInstanceId, variables);

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        String startEventId = bpmn.getStartEvent().getId();

        UUID parentTokenId = null;
        if (parentActivityId != null) {
            Activity activity = dbService.getActivity(parentActivityId);
            Token token = dbService.getToken(activity.getToken());
            parentTokenId = token.getId();
        }
        Token token = dbService.createToken(parentTokenId);

        execute(processInstanceId, token.getId(), startEventId);

        return processInstanceId;
    }

    private UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstance.getProcessDefinitionId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        String targetRef = flow.getTargetRef();
        String sourceRef = flow.getSourceRef();
        BpmnElementModel target = bpmn.getElement(targetRef);
        BpmnElementModel source = bpmn.getElement(sourceRef);

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
        }

        return flowActivityId;
    }

    private void processEndEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

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
            dbService.completeActivity(parentActivityId);

            Activity parentActivity = dbService.getActivity(parentActivityId);
            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentActivity.getProcessInstanceId());
            UUID parentProcessDefinitionId = parentProcessInstance.getProcessDefinitionId();
            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessDefinitionId);

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            dbService.setVariables(parentProcessInstanceId, variables);

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());

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

    private void processStartEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

}
