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
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.handler.CompletionService;
import com.zorrodev.bpm.engine.handler.ElementHandler;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.handler.ExecutionCtx;
import com.zorrodev.bpm.engine.handler.EventTrigger;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.HandlerRegistry;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.ArrayDeque;
import java.util.Deque;
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
@Transactional
public class ActivityServiceImpl implements ActivityService, TokenExecutor {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final DmnService dmnService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final ExecutionContext executionContext;
    private final HandlerRegistry handlerRegistry;
    private final com.zorrodev.bpm.engine.handler.MultiInstanceExecutor multiInstanceExecutor;
    private final com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;
    private final com.zorrodev.bpm.engine.handler.BoundaryScheduler boundaryScheduler;
    private final EventTrigger eventTrigger;
    private final com.zorrodev.bpm.engine.handler.CompletionService completionService;
    private final com.zorrodev.bpm.engine.handler.IncidentService incidentService;
    private final com.zorrodev.bpm.engine.handler.ErrorEscalationThrower errorEscalationThrower;
    /**
     * WO-C8-25: element-listener phases (gateways/events) — one narrow collaborator instead
     * of inlining phase/table/enqueue logic here (god-class discipline: this class already
     * carries 16 dependencies; the alternative was 4 more).
     */
    private final com.zorrodev.bpm.engine.handler.ElementListenerPhaseService elementListenerPhaseService;
    private final FlowNavigator flowNavigator;

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     */
    public void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, element, this);
    }

    /**
     * Re-evaluates the instance's conditional events after a variable change: any parked conditional catch
     * whose condition is now true is signalled, and any conditional boundary on an active host whose
     * condition is now true is fired. A thread-local guard stops a fired event's own continuation (which
     * runs through {@link #signal}/{@link #fireBoundary}) from recursively re-triggering this pass.
     */
    public void triggerConditionalEvents(UUID processInstanceId) {
        completionService.triggerConditionalEvents(processInstanceId, this);
    }

    /**
     * Parks the token at the failing element as an incident instead of propagating the exception
     * (which would roll back the whole process transaction). Marks the element's activity ERROR
     * and records an incident the operator can later resolve via
     * {@link #resolveIncident(UUID, List)}, which re-executes the element.
     */
    private void raiseIncident(UUID processInstanceId, UUID tokenId, BpmnElementModel element, Exception e) {
        incidentService.raiseIncident(processInstanceId, tokenId, element, e);
    }

    @Override
    public void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId) {
        ProcessInstance processInstanceEntity = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstanceEntity.getProcessDefinitionId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);

        execute(processInstanceId, tokenId, bpmn, element);
    }

    public void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element == null) {
            throw new IllegalStateException("Cannot execute a null element — a referenced element was not found in the process definition");
        }
        int depth = executionContext.enterDepth(element.getId(), processInstanceId);
        try {
            BpmnElementType type = element.getType();
            // Side-effect-free registry lookup, done once: the handler itself runs only
            // in handler.handle() below, never on the parked path.
            ElementHandler handler = handlerRegistry.get(type);

            // WO-C8-25: element-listener phase (gateways/events) parks before anything else
            // runs — listeners fire outermost, including before job-based routing below.
            // Service/user tasks and handler-less elements never park (see tryParkPhase).
            if (elementListenerPhaseService.tryParkPhase(processInstanceId, tokenId, element, handler != null)) {
                return;
            }

            if (isJobBasedEvent(element)) {
                // WO-C8-16: job-based end/throw events park as jobs via the service-task
                // mechanism (createServiceTask + enqueueAfterCommit); the worker's completion
                // continues the flow through CompletionService.completeServiceTask.
                enterServiceTask(processInstanceId, tokenId, element);
                return;
            }
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
            ExecutionCtx ctx = new ExecutionCtx(processInstanceId, tokenId, this, executionContext);
            try {
                handler.handle(ctx, bpmn, element);
            } catch (EngineException e) {
                // engine-level aborts (e.g. depth limit) propagate; they are not element failures
                throw e;
            } catch (Exception e) {
                // any element failure (bad FEEL result, variable conversion, service error, ...)
                // parks the token as an incident instead of rolling back the whole process
                raiseIncident(processInstanceId, tokenId, element, e);
            }
        } finally {
            executionContext.exitDepth(depth);
        }
    }



    public void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        ((com.zorrodev.bpm.engine.handler.ServiceTaskHandler) handlerRegistry.get(BpmnElementType.SERVICE_TASK))
            .enter(processInstanceId, token, bpmnElement, this);
    }

    /**
     * WO-C8-16: true for plain end events and job-capable throw events carrying
     * {@code zeebe:taskDefinition} (Camunda 8 "outbound message via worker" pattern).
     * Rationale for the explicit list (incl. HOLD-found exclusions): WO-C8-16.md.
     */
    private boolean isJobBasedEvent(BpmnElementModel element) {
        BpmnElementType type = element.getType();
        boolean eventKind = type == BpmnElementType.END_EVENT
            || type == BpmnElementType.INTERMEDIATE_THROW_EVENT
            || type == BpmnElementType.MESSAGE_THROW_EVENT;
        if (!eventKind) {
            return false;
        }
        String job = elementSupport.serviceTaskJob(element);
        return job != null && !job.isBlank();
    }

    @Override
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        completionService.failServiceTask(serviceTaskId, errorMessage, retries);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        completionService.completeServiceTask(serviceTaskId, variables, this);
    }

    @Override
    public void completeAdHocScopeJob(UUID scopeActivityId, com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result) {
        completionService.completeAdHocScopeJob(scopeActivityId, result, this);
    }


    /**
     * Evaluates a message subscriber's correlation-key FEEL expression.
     */
    @Override
    public String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId) {
        return elementSupport.evaluateCorrelationKey(element, processInstanceId);
    }

    @Override
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables) {
        completionService.completeUserTask(userTaskId, variables, this);
    }

    @Override
    public void assignUserTask(UUID taskId, String assignee) {
        completionService.assignUserTask(taskId, assignee);
    }

    @Override
    public void claimUserTask(UUID taskId, String assignee) {
        completionService.claimUserTask(taskId, assignee);
    }

    @Override
    public void signal(UUID activityId, List<ProcessVariable> variables) {
        completionService.signal(activityId, variables, this);
    }

    @Override
    public void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId) {
        Activity host = dbService.getActivity(hostActivityId);
        if (eventTrigger.fireBoundary(hostActivityId, boundaryElementId, List.of(), this)) {
            triggerConditionalEvents(host.getProcessInstanceId());
        }
    }

    @Override
    public void fireEventSubprocessTimer(UUID processInstanceId, String eventSubprocessId) {
        // the timer job is already marked fired (one-shot), so it never re-fires regardless of interrupting
        eventTrigger.triggerEventSubprocess(processInstanceId, eventSubprocessId, List.of(), this);
    }

    @Override
    public void correlateMessage(String messageName, UUID processInstanceId, List<ProcessVariable> variables) {
        correlateMessage(messageName, null, processInstanceId, variables);
    }

    @Override
    public void correlateMessage(String messageName, String correlationKey, UUID processInstanceId, List<ProcessVariable> variables) {
        correlateCounted(messageName, correlationKey, processInstanceId, variables);
    }

    /**
     * WO-DIFF-5: counted variant backing both the silent internal correlate path and the
     * reporting {@link #publishMessage}. Body is the pre-existing correlation logic verbatim,
     * plus two counters: message-start launches ({@code started}) and actually-woken
     * subscriptions ({@code correlated} — a subscription whose consume lost the race is not
     * counted, it was not woken by this call).
     */
    @Override
    public com.zorrodev.bpm.engine.dto.MessagePublishResult publishMessage(String messageName, String correlationKey,
            UUID processInstanceId, List<ProcessVariable> variables) {
        int[] counts = correlateCounted(messageName, correlationKey, processInstanceId, variables);
        return new com.zorrodev.bpm.engine.dto.MessagePublishResult(counts[0], counts[1]);
    }

    /**
     * WO-DIFF-5: counted core of {@link #correlateMessage(String, String, UUID, List)}.
     * Returns {@code [correlated, started]}. Pre-existing body verbatim (incl. WO-REL-31 CR-3
     * keyset-paged fan-out); only the two counters are new.
     */
    private int[] correlateCounted(String messageName, String correlationKey, UUID processInstanceId, List<ProcessVariable> variables) {
        int correlated = 0;
        int started = 0;
        // untargeted correlation by name (no instance, no key) may also start new instances via message starts
        List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> startSubscriptions =
            (processInstanceId == null && correlationKey == null) ? dbService.findMessageStartSubscriptions(messageName) : List.of();

        if (!startSubscriptions.isEmpty()) {
            for (com.zorrodev.bpm.engine.dto.MessageStartSubscription start : startSubscriptions) {
                log.info("Message '{}' starting a new instance of {} at {}", messageName, start.getProcessDefinitionId(), start.getElementId());
                eventTrigger.startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables, this);
                started++;
            }
        }

        // WO-REL-31 CR-3: keyset-paged fan-out — never loads the full subscription set at once
        // (10k+ subscribers = OOM) and iterates in deterministic id-DESC order.
        // Pages of non-interrupting event-subprocess subscriptions (consumed=false kept) are
        // never revisited: ids from earlier pages are always > cursor, so no infinite loop.
        boolean sawSubscriptions = false;
        UUID cursor = null;
        while (true) {
            List<MessageSubscription> page = correlationKey != null
                ? dbService.findMessageSubscriptionsByKey(messageName, correlationKey, cursor)
                : dbService.findMessageSubscriptions(messageName, processInstanceId, cursor);
            if (page.isEmpty()) {
                break;
            }
            sawSubscriptions = true;
            UUID nextCursor = page.get(page.size() - 1).getId(); // min id in this page (id-DESC ordering)
            for (MessageSubscription subscription : page) {
                if (processInstanceId != null && !processInstanceId.equals(subscription.getProcessInstanceId())) {
                    continue; // key-path narrowing: key query may return sibling instances — filtered post-fetch
                }
                if (subscription.getEventSubprocessId() != null) {
                    boolean interrupting = eventTrigger.isInterruptingEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId());
                    boolean shouldFire = interrupting ? dbService.consumeMessageSubscription(subscription.getId()) : true;
                    if (shouldFire) {
                        log.info("Correlating message '{}' to event sub-process {} on instance {}", messageName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                        eventTrigger.triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables, this);
                        correlated++;
                    }
                    continue;
                }
                if (dbService.consumeMessageSubscription(subscription.getId())) {
                    if (subscription.getBoundaryElementId() != null) {
                        log.info("Correlating message '{}' to boundary {} on instance {} activity {}", messageName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                        eventTrigger.fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables, this);
                    } else {
                        log.info("Correlating message '{}' to instance {} activity {}", messageName, subscription.getProcessInstanceId(), subscription.getActivityId());
                        signal(subscription.getActivityId(), variables);
                    }
                    correlated++;
                }
            }
            if (page.size() < DBService.FAN_OUT_BATCH_SIZE) {
                break; // last page — fewer than a full page means no more rows beyond the cursor
            }
            cursor = nextCursor;
        }
        if (!sawSubscriptions && startSubscriptions.isEmpty()) {
            log.info("No active subscription for message '{}' (instance {}, key {})", messageName, processInstanceId, correlationKey);
        }
        return new int[]{correlated, started};
    }

    /**
     * Broadcasts a signal: wakes every active subscription with the matching name, across process
     * instances (1:N). Each waiting activity is signalled under its own per-instance lock (via
     * {@link #signal}), so concurrent broadcasts/correlations stay isolated per instance.
     */
    @Override
    public void broadcastSignal(String signalName, List<ProcessVariable> variables) {
        eventTrigger.broadcastSignal(signalName, variables, this, this::signal);
    }

    @Override
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables) {
        incidentService.resolveIncident(incidentId, variables, this);
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        return startProcessInstance(parentActivityId, processDefinitionId, variables, null);
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId,
            List<ProcessVariable> variables, String claimedInitiator) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        if (bpmn.getStartEvent() == null) {
            throw new EngineException("Process definition " + processDefinitionId
                + " has no plain start event; it can only be started by a message or timer start event");
        }
        return eventTrigger.startProcessInstanceAt(parentActivityId, processDefinitionId,
            bpmn.getStartEvent().getId(), variables, claimedInitiator, this);
    }

    @Override
    public UUID startProcessInstanceFromStartEvent(UUID processDefinitionId, String startElementId, List<ProcessVariable> variables) {
        return eventTrigger.startProcessInstanceAt(null, processDefinitionId, startElementId, variables, this);
    }

    public UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        return flowNavigator.processFlow(processInstanceId, tokenId, flowId, processExpression, defaultFlow);
    }

    /**
     * Ends the current branch at an end event: if the token is inside an embedded subprocess scope,
     * completes the container and continues the parent token from the subprocess's outgoing flows;
     * otherwise completes the process instance and continues the parent call activity (if any). Shared
     * by plain and escalation end events.
     */
    public void finishBranch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn) {
        flowNavigator.finishBranch(processInstanceId, tokenId, bpmn, this);
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
    public boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode) {
        return errorEscalationThrower.throwError(processInstanceId, tokenId, errorCode, null, this);
    }

    /**
     * WO-DIFF-5: manual BPMN-error throw from a service task. Stale tasks (anything but
     * CREATED/IN_PROGRESS — already completed, cancelled by a boundary, errored) are a 409
     * {@code THROW_ERROR_STALE}: silently returning {@code handled=false} + an incident for a
     * task that is not even live would fabricate failure evidence.
     */
    @Override
    public com.zorrodev.bpm.engine.dto.ThrowServiceTaskErrorResult throwServiceTaskError(UUID serviceTaskId,
            String errorCode, List<ProcessVariable> variables) {
        com.zorrodev.bpm.engine.dto.Activity activity = elementSupport.lockAndReload(serviceTaskId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "THROW_ERROR_STALE",
                "Service task " + serviceTaskId + " is " + activity.getStatus() + " — cannot throw an error from it",
                Map.of("serviceTaskId", serviceTaskId.toString()));
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }
        boolean handled = errorEscalationThrower.throwError(processInstanceId, activity.getToken(),
            errorCode, activity.getBpmnElementId(), this);
        if (handled) {
            return new com.zorrodev.bpm.engine.dto.ThrowServiceTaskErrorResult(true, null);
        }
        // mirror of EndEventHandler.ErrorEndEvent: a manual throw is never quieter than automatic
        dbService.errorActivity(serviceTaskId);
        UUID incidentId = dbService.createIncident(serviceTaskId,
            "Unhandled BPMN error" + (errorCode != null ? " '" + errorCode + "'" : ""));
        return new com.zorrodev.bpm.engine.dto.ThrowServiceTaskErrorResult(false, incidentId);
    }



    public String escalationCode(BpmnElementModel element) {
        return errorEscalationThrower.escalationCode(element);
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
    public boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode) {
        return errorEscalationThrower.throwEscalation(processInstanceId, tokenId, escalationCode, this);
    }

}
