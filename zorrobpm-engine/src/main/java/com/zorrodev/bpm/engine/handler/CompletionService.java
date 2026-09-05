package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Collaborator extracted from ActivityServiceImpl (WO-AUD-24).
 * Owns the completion/signal logic: completeUserTask, completeServiceTask, failServiceTask, signal,
 * triggerConditionalEvents, and the shared lockAndReload/isBehindEventBasedGateway helpers.
 * TokenExecutor is passed as a parameter (port) to avoid circular bean dependencies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletionService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final ElementSupport elementSupport;
    private final MultiInstanceExecutor multiInstanceExecutor;
    private final FlowNavigator flowNavigator;
    private final EventTrigger eventTrigger;
    private final ExecutionContext executionContext;

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

    /**
     * Completes a user task: applies variables, marks the activity and user task done, handles
     * IO mappings and multi-instance, then follows outgoing flows and re-evaluates conditionals.
     */
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables, TokenExecutor executor) {
        Activity activity = elementSupport.lockAndReload(userTaskId);
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

        elementSupport.applyIoMappings(processInstanceId, userTaskId, bpmnElement, false);
        // multi-instance: append this instance's outputElement to the outputCollection before its scoped
        // variables (inputElement/loopCounter) are dropped
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, userTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, userTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, token, bpmnElement, userTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        flowNavigator.proceedToOutgoing(processInstanceId, token, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * Completes a service task: applies variables, marks the activity and service task done, handles
     * IO mappings and multi-instance, then follows outgoing flows and re-evaluates conditionals.
     *
     * <p>WO-C8-11: if a start listener is in flight ({@code pendingListenerIndex != null}), a
     * completion means "this listener finished" — apply its variables, advance to the next
     * listener (or to the real job) and {@code return} WITHOUT completing the activity and
     * WITHOUT moving the token. Only the real job's completion follows the path below.
     */
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables, TokenExecutor executor) {
        Activity activity = elementSupport.lockAndReload(serviceTaskId);
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

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        // WO-C8-11: listener-step completion — read only for elements that declare listeners,
        // so the common path never touches the new state.
        List<ListenerModel> startListeners = elementSupport.serviceTaskStartListeners(bpmnElement);
        if (!startListeners.isEmpty()) {
            Integer pending = dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
            if (pending != null && pending >= 0 && pending < startListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                if (pending + 1 < startListeners.size()) {
                    dbService.setPendingListenerIndex(serviceTaskId, pending + 1);
                } else {
                    dbService.setPendingListenerIndex(serviceTaskId, null);
                }
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                log.info("{}/{}: Completing start listener {} of {}: {}/{}", processInstanceId, tokenId,
                    pending, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                return;
            }
            // Out-of-bounds/foreign index (model redeployed mid-flight): fall through to the
            // normal path below (fail-open, completes and moves the token) rather than stranding.
        }

        // WO-C8-11b: end-listener phase — read only for elements that declare end listeners,
        // so the common path never touches the new state.
        List<ListenerModel> endListeners = elementSupport.serviceTaskEndListeners(bpmnElement);
        Integer pendingEnd = endListeners.isEmpty() ? null : dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId);
        if (!endListeners.isEmpty() && pendingEnd == null) {
            // Real-job completion → open the end phase WITHOUT completing the activity
            // (design CTO: the element is not complete until its end listeners ran, so the
            // status guard above stays green for every listener completion).
            dbService.setVariables(processInstanceId, variables);
            dbService.setPendingEndListenerIndex(serviceTaskId, 0);
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Real job done, opening end-listener phase of {}: {}/{}", processInstanceId, tokenId,
                activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return;
        }
        if (pendingEnd != null && pendingEnd >= 0 && pendingEnd < endListeners.size()) {
            if (pendingEnd + 1 < endListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingEndListenerIndex(serviceTaskId, pendingEnd + 1);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                log.info("{}/{}: Completing end listener {} of {}: {}/{}", processInstanceId, tokenId,
                    pendingEnd, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                return;
            }
            dbService.setPendingEndListenerIndex(serviceTaskId, null);
            // Last end listener done → fall through to the real completion tail below
            // (it applies this completion's variables itself).
        }
        // No end phase (or corrupt/foreign end index): fail-open into the normal tail below.

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        if (bpmnElement.getType() == BpmnElementType.END_EVENT) {
            // WO-C8-16: job-based end event — the worker's completion ends the branch exactly
            // like EndEventHandler (finishBranch; no proceedToOutgoing/conditional pass, which
            // would strand the token: proceedToOutgoing is a no-op without outgoing flows).
            // Only plain ends can arrive here (typed ends never park — see isJobBasedEvent).
            flowNavigator.finishBranch(processInstanceId, tokenId, bpmn, executor);
            return;
        }

        elementSupport.applyIoMappings(processInstanceId, serviceTaskId, bpmnElement, false);
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, serviceTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, serviceTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, tokenId, bpmnElement, serviceTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * Reports a service-task (job) failure from a worker. {@code retries} follows Camunda {@code failJob}:
     * when non-null the retry budget is set to it ({@code 0} raises the incident immediately); when null the
     * budget is decremented by one. While retries remain the job is re-dispatched; when exhausted the activity
     * is marked ERROR and an incident carrying {@code errorMessage} is raised. The token stays parked.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        Activity activity = elementSupport.lockAndReload(serviceTaskId);
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
     * Resumes a token parked at a wait state (intermediate/message/timer catch event):
     * applies the given variables, completes the waiting activity and follows its outgoing flows.
     * Called by the timer scheduler and message-correlation subsystems.
     */
    public void signal(UUID activityId, List<ProcessVariable> variables, TokenExecutor executor) {
        Activity activity = elementSupport.lockAndReload(activityId);
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

        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * Re-evaluates the instance's conditional events after a variable change: any parked conditional catch
     * whose condition is now true is signalled, and any conditional boundary on an active host whose
     * condition is now true is fired. A thread-local guard stops a fired event's own continuation (which
     * runs through {@link #signal}/{@link EventTrigger#fireBoundary}) from recursively re-triggering this pass.
     */
    public void triggerConditionalEvents(UUID processInstanceId, TokenExecutor executor) {
        if (executionContext.isEvaluatingConditionals()) {
            return;
        }
        executionContext.setEvaluatingConditionals(true);
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
                    if (eventTrigger.conditionHolds(element, variables)) {
                        log.info("{}: Conditional catch {} satisfied, firing", processInstanceId, element.getId());
                        signal(activity.getId(), List.of(), executor);
                    }
                } else {
                    for (BpmnElementModel boundary : eventTrigger.findConditionalBoundaries(bpmn, element.getId())) {
                        if (eventTrigger.conditionHolds(boundary, variables)) {
                            log.info("{}: Conditional boundary {} satisfied, firing on host {}", processInstanceId, boundary.getId(), element.getId());
                            if (eventTrigger.fireBoundary(activity.getId(), boundary.getId(), List.of(), executor)) {
                                triggerConditionalEvents(processInstanceId, executor);
                            }
                        }
                    }
                }
            }
        } finally {
            executionContext.setEvaluatingConditionals(false);
        }
    }
}
