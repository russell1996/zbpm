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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final UserTaskHandler userTaskHandler;
    private final ElementListenerPhaseService elementListenerPhaseService;

    /**
     * WO-C8-25 (extends WO-C8-24): element kinds whose jobs never live in
     * {@code service_tasks} rows (user tasks — C8-21/C8-24 phases; gateways and events —
     * C8-25 phases). A service-task completion arriving for them with no listener phase
     * open is spurious (e.g. a redelivered listener completion; the broker is at-least-once)
     * and is ignored instead of falling into the service-task tail (no row → orElseThrow).
     * Any FUTURE kind defaults to the tail (loud 500) — fail-closed by construction.
     */
    private static final Set<BpmnElementType> PHASE_ONLY_ELEMENT_TYPES = EnumSet.of(
        BpmnElementType.USER_TASK,
        BpmnElementType.EXCLUSIVE_GATEWAY, BpmnElementType.PARALLEL_GATEWAY,
        BpmnElementType.EVENT_BASED_GATEWAY, BpmnElementType.INCLUSIVE_GATEWAY,
        BpmnElementType.START_EVENT, BpmnElementType.MESSAGE_START_EVENT,
        BpmnElementType.TIMER_START_EVENT, BpmnElementType.SIGNAL_START_EVENT,
        BpmnElementType.END_EVENT, BpmnElementType.TERMINATE_END_EVENT,
        BpmnElementType.ERROR_END_EVENT, BpmnElementType.ESCALATION_END_EVENT,
        BpmnElementType.CANCEL_END_EVENT,
        BpmnElementType.INTERMEDIATE_CATCH_EVENT, BpmnElementType.MESSAGE_CATCH_EVENT,
        BpmnElementType.TIMER_CATCH_EVENT, BpmnElementType.SIGNAL_CATCH_EVENT,
        BpmnElementType.LINK_CATCH_EVENT, BpmnElementType.CONDITIONAL_CATCH_EVENT,
        BpmnElementType.INTERMEDIATE_THROW_EVENT, BpmnElementType.MESSAGE_THROW_EVENT,
        BpmnElementType.SIGNAL_THROW_EVENT, BpmnElementType.LINK_THROW_EVENT,
        BpmnElementType.ESCALATION_THROW_EVENT, BpmnElementType.COMPENSATION_THROW_EVENT);

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
     *
     * <p>WO-C8-24: if the element declares {@code completing} listeners and no phase is open,
     * this call OPENS the phase instead of completing — variables applied durably first,
     * listener job dispatched, activity stays put, token parked. A repeat call while the
     * phase is open throws {@code TaskCompletionInProgressException} (REST → 409).
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

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        // WO-C8-24: completing-listener phase — read only for elements that declare
        // completing listeners, so the common path never touches the new state.
        List<ListenerModel> completingListeners = elementSupport.userTaskCompletingListeners(bpmnElement);
        if (!completingListeners.isEmpty()) {
            Integer pendingCompleting = dbService.getPendingCompletingListenerIndex(userTaskId);
            if (pendingCompleting == null) {
                // First complete → open the phase. Variables go FIRST and durably: if a
                // listener fails and the phase waits for incident resolve, the caller's
                // variables must already be in the instance (WO step 3).
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingCompletingListenerIndex(userTaskId, 0);
                dbService.setCompletingListenerRetriesRemaining(userTaskId,
                    elementSupport.listenerBudget(completingListeners.get(0)));
                serviceTaskEnqueueService.enqueueAfterCommit(userTaskId);
                log.info("{}/{}: Completing user task, opening completing-listener phase of {}: {}/{}",
                    processInstanceId, token, activity.getBpmnElementId(), userTaskId, activity.getBpmnElementId());
                return;
            }
            // Phase already open: a repeat complete is a client conflict (409), never a
            // silent re-completion and never a 500 (WO step 5; closes the R1-review defect
            // class on this path — mid-phase REST used to fall into a 500).
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already completing"
                    + " (completing listener " + pendingCompleting + " in flight) — wait for it to finish");
        }

        finishUserTaskCompletion(processInstanceId, token, userTaskId, variables, bpmn, bpmnElement, executor);
    }

    /**
     * WO-C8-24: the real user-task completion tail (variables already applied by the caller).
     * One body shared by the immediate path above and the last completing listener below —
     * the two cannot diverge.
     */
    private void finishUserTaskCompletion(UUID processInstanceId, UUID token, UUID userTaskId,
            List<ProcessVariable> variables, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement,
            TokenExecutor executor) {
        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, BpmnElementType.USER_TASK, userTaskId, bpmnElement.getId());

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
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below,
        // byte-identical (one indexed PK read extra on the completion path).
        Optional<ElementListenerPhaseService.Resume> phaseResume =
            elementListenerPhaseService.completePhaseListener(serviceTaskId, variables);
        if (phaseResume.isPresent()) {
            ElementListenerPhaseService.Resume resume = phaseResume.get();
            if (resume.finished()) {
                executionContext.setResumingListenerPhase(true);
                try {
                    executor.execute(resume.processInstanceId(), resume.tokenId(), resume.bpmnElementId());
                } finally {
                    executionContext.setResumingListenerPhase(false);
                }
            }
            return;
        }
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

        // WO-C8-21r2: creating-listener completion — the phase index lives on the ACTIVITY
        // row (no user_tasks row exists until the task is really created). A completion means
        // "this listener finished": advance to the next listener (with its own retry budget),
        // or create the task after the last one — WITHOUT touching the service-task tail below.
        List<ListenerModel> creatingListeners = elementSupport.userTaskCreatingListeners(bpmnElement);
        if (!creatingListeners.isEmpty()) {
            Integer pendingCreating = dbService.getPendingCreatingListenerIndex(serviceTaskId);
            if (pendingCreating != null) {
                if (pendingCreating >= 0 && pendingCreating < creatingListeners.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingCreating + 1 < creatingListeners.size()) {
                        dbService.setPendingCreatingListenerIndex(serviceTaskId, pendingCreating + 1);
                        dbService.setCreatingListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(creatingListeners.get(pendingCreating + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing creating listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingCreating, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return;
                    }
                    dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
                    dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
                    userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement);
                    userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
                    log.info("{}/{}: Last creating listener done, task created: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return;
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight, phase MEANT open):
                // fail-open into task creation rather than stranding (mirror of the C8-11
                // fail-open below). A null index is NOT this case — see below.
                dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
                dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
                userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement);
                userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
                log.info("{}/{}: Out-of-bounds creating listener index, task created fail-open: {}/{}",
                    processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
                return;
            }
            // Null index = NO creating phase open: this completion is not a creating-listener
            // completion (e.g. a completing-listener job on an element declaring both kinds) —
            // fall through so the completing branch below sees it. WO-C8-24: the old code
            // fail-opened here and re-created the task on every later completion.
        }

        // WO-C8-24: completing-listener completion — read only for elements that declare
        // completing listeners, so the common path never touches the new state. A completion
        // means "this listener finished": advance to the next listener (with its own retry
        // budget), or run the real user-task completion tail after the last one — WITHOUT
        // touching the service-task tail below (there is no service_tasks row for a user
        // task; falling through would complete a foreign tail and move the token wrongly).
        List<ListenerModel> completingListeners = elementSupport.userTaskCompletingListeners(bpmnElement);
        if (!completingListeners.isEmpty()) {
            Integer pendingCompleting = dbService.getPendingCompletingListenerIndex(serviceTaskId);
            if (pendingCompleting != null) {
                if (pendingCompleting >= 0 && pendingCompleting < completingListeners.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingCompleting + 1 < completingListeners.size()) {
                        dbService.setPendingCompletingListenerIndex(serviceTaskId, pendingCompleting + 1);
                        dbService.setCompletingListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(completingListeners.get(pendingCompleting + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing completing listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingCompleting, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return;
                    }
                    dbService.setPendingCompletingListenerIndex(serviceTaskId, null);
                    dbService.setCompletingListenerRetriesRemaining(serviceTaskId, null);
                    finishUserTaskCompletion(processInstanceId, tokenId, serviceTaskId, variables, bpmn, bpmnElement, executor);
                    log.info("{}/{}: Last completing listener done, task completed: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return;
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight, phase MEANT open):
                // fail-open into the real user-task completion (same spirit as the C8-11
                // fail-open) rather than stranding — the task was asked to complete, so
                // complete it. NOT a fall-through into the service-task tail below (no
                // service_tasks row for a user task).
                dbService.setPendingCompletingListenerIndex(serviceTaskId, null);
                dbService.setCompletingListenerRetriesRemaining(serviceTaskId, null);
                finishUserTaskCompletion(processInstanceId, tokenId, serviceTaskId, variables, bpmn, bpmnElement, executor);
                log.info("{}/{}: Out-of-bounds completing listener index, task completed fail-open: {}/{}",
                    processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
                return;
            }
            // Null index = NO completing phase open: fall through (a creating-listener
            // completion on an element declaring both kinds is handled above; anything else
            // reaching the user-task guard below is spurious and ignored there).
        }

        // WO-C8-25 (extends WO-C8-24): element kinds whose jobs never live in
        // service_tasks rows have no "real" job — a service-task completion arriving here
        // with no listener phase open on any branch above is spurious (e.g. a redelivered
        // listener completion; the broker is at-least-once). Ignore it instead of falling
        // into the service-task branches/tail below (no service_tasks row → orElseThrow).
        // Same philosophy as the status guard at the top of this method. Job-based
        // elements (taskDefinition present — C8-16 end/throw events) are EXEMPT: their jobs
        // do own service_tasks rows and complete through the normal path below.
        // Kinds owning service_tasks rows never match otherwise, so their path — including
        // the orElseThrow loudness on corruption — is unchanged. Future kinds default to
        // the tail (loud) — fail-closed by construction.
        String elementJob = elementSupport.serviceTaskJob(bpmnElement);
        if (PHASE_ONLY_ELEMENT_TYPES.contains(bpmnElement.getType())
            && (elementJob == null || elementJob.isBlank())) {
            log.info("Ignoring service-task completion of {} {} with no listener phase in flight",
                bpmnElement.getType(), serviceTaskId);
            return;
        }

        // WO-C8-11: listener-step completion — read only for elements that declare listeners,
        // so the common path never touches the new state.
        List<ListenerModel> startListeners = elementSupport.serviceTaskStartListeners(bpmnElement);
        if (!startListeners.isEmpty()) {
            Integer pending = dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
            if (pending != null && pending >= 0 && pending < startListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                if (pending + 1 < startListeners.size()) {
                    dbService.setPendingListenerIndex(serviceTaskId, pending + 1);
                    // WO-C8-21r2: the next listener owns its own retry budget (model value,
                    // default 3) — listener failures no longer eat the real job's budget.
                    dbService.setServiceTaskRetries(serviceTaskId,
                        elementSupport.listenerBudget(startListeners.get(pending + 1)));
                } else {
                    dbService.setPendingListenerIndex(serviceTaskId, null);
                    // WO-C8-21r2: the real job dispatches next with its own budget, fresh —
                    // whatever the listeners consumed stays with them.
                    dbService.setServiceTaskRetries(serviceTaskId,
                        elementSupport.serviceTaskRetries(bpmnElement));
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
            // WO-C8-21r2: the in-flight end listener owns its own retry budget.
            dbService.setServiceTaskRetries(serviceTaskId,
                elementSupport.listenerBudget(endListeners.get(0)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Real job done, opening end-listener phase of {}: {}/{}", processInstanceId, tokenId,
                activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return;
        }
        if (pendingEnd != null && pendingEnd >= 0 && pendingEnd < endListeners.size()) {
            if (pendingEnd + 1 < endListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingEndListenerIndex(serviceTaskId, pendingEnd + 1);
                // WO-C8-21r2: the next end listener owns its own retry budget.
                dbService.setServiceTaskRetries(serviceTaskId,
                    elementSupport.listenerBudget(endListeners.get(pendingEnd + 1)));
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
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below.
        if (elementListenerPhaseService.failPhaseListener(serviceTaskId, errorMessage, retries)) {
            return;
        }
        Activity activity = elementSupport.lockAndReload(serviceTaskId);
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished (redelivered failure, or interrupted by a boundary) — ignore
            log.info("Ignoring failure of service task {} in status {}", serviceTaskId, activity.getStatus());
            return;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Service task failed" : errorMessage;
        // WO-C8-21r2: a failing creating-listener job draws from its own durable budget
        // (model value, default 3 — set at phase open/advance; there is no service_tasks
        // row for a user task, so the shared budget path below would orElseThrow).
        // Gated on the activity type first, so the common service-task fail path never
        // pays for the bpmn load below.
        if (activity.getType() == BpmnElementType.USER_TASK) {
            ProcessInstance failPi = dbService.getProcessInstance(activity.getProcessInstanceId());
            BpmnProcessDefinitionModel failBpmn = bpmnService.getProcessDefinitionModelById(failPi.getProcessDefinitionId());
            BpmnElementModel failElement = failBpmn.getElement(activity.getBpmnElementId());
            if (!elementSupport.userTaskCreatingListeners(failElement).isEmpty()
                && dbService.getPendingCreatingListenerIndex(serviceTaskId) != null) {
                int remaining;
                if (retries != null) {
                    // Camunda failJob semantics: explicit value sets the budget (0 → incident now).
                    dbService.setCreatingListenerRetriesRemaining(serviceTaskId, retries);
                    remaining = retries;
                } else {
                    Integer budget = dbService.getCreatingListenerRetriesRemaining(serviceTaskId);
                    remaining = (budget == null ? 0 : budget) - 1;
                    dbService.setCreatingListenerRetriesRemaining(serviceTaskId, remaining);
                }
                if (remaining > 0) {
                    log.info("{}/{}: User task creating listener {} failed ({} retries left), re-dispatching: {}",
                        activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                    serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                    return;
                }
                log.info("{}/{}: User task creating listener {} failed, retries exhausted — raising incident: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId, message);
                return;
            }
            // WO-C8-24: same budget mechanics for an in-flight completing listener — the
            // task stays uncompleted, the token stays parked (WO step 6). Shared element
            // load above (failElement) is reused, not reloaded.
            if (!elementSupport.userTaskCompletingListeners(failElement).isEmpty()
                && dbService.getPendingCompletingListenerIndex(serviceTaskId) != null) {
                int remaining;
                if (retries != null) {
                    dbService.setCompletingListenerRetriesRemaining(serviceTaskId, retries);
                    remaining = retries;
                } else {
                    Integer budget = dbService.getCompletingListenerRetriesRemaining(serviceTaskId);
                    remaining = (budget == null ? 0 : budget) - 1;
                    dbService.setCompletingListenerRetriesRemaining(serviceTaskId, remaining);
                }
                if (remaining > 0) {
                    log.info("{}/{}: User task completing listener {} failed ({} retries left), re-dispatching: {}",
                        activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                    serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                    return;
                }
                log.info("{}/{}: User task completing listener {} failed, retries exhausted — raising incident: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId, message);
                return;
            }
        }
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
