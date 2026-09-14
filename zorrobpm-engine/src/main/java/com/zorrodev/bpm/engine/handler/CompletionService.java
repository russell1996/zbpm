package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
@Transactional
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
    private final AdHocSubProcessHandler adHocSubProcessHandler;
    private final tools.jackson.databind.ObjectMapper objectMapper;

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
        if (!isUserTaskCompletionAllowed(userTaskId, activity)) {
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        rejectOpenAssigningPhase(userTaskId, activity, bpmnElement);
        if (openUpdatingPhaseOnVariables(userTaskId, variables, processInstanceId, token, bpmnElement, activity)) {
            return;
        }
        if (openCompletingPhase(userTaskId, variables, processInstanceId, token, bpmnElement, activity)) {
            return;
        }

        finishUserTaskCompletion(processInstanceId, token, userTaskId, variables, bpmn, bpmnElement, executor);
    }

    /**
     * WO-DEBT-6 S3: status guard of {@link #completeUserTask}.
     * Verbatim block except mechanical return plumbing. Returns true when the completion may proceed.
     */
    private boolean isUserTaskCompletionAllowed(UUID userTaskId, Activity activity) {
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete — ignore a duplicate/late completion, a boundary-timer
            // interruption (CANCELLED) or a task superseded by incident-resolve (ERROR) to avoid double execution
            log.info("Ignoring completion of user task {} in status {}", userTaskId, activity.getStatus());
            return false;
        }
        return true;
    }

    /**
     * WO-DEBT-6 S3: assigning-phase guard of {@link #completeUserTask} (WO-C8-28).
     * Verbatim block (void, zero-touch).
     */
    private void rejectOpenAssigningPhase(UUID userTaskId, Activity activity, BpmnElementModel bpmnElement) {
        // WO-C8-28: a complete attempted while another listener phase is open is a
        // client conflict (409), never a silent double transition — the in-flight
        // phase owns this task until its listeners finish. Same exception class as
        // completing (the REST catch maps by class); the message names the open phase.
        // (An updating phase is opened by this very method below; a completing phase
        // open hits its own 409 in its branch.)
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        Integer pendingAssigningOnComplete =
            assigningListeners.isEmpty() ? null : dbService.getPendingAssigningListenerIndex(userTaskId);
        if (pendingAssigningOnComplete != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already assigning"
                    + " (assigning listener " + pendingAssigningOnComplete + " in flight) — wait for it to finish");
        }
    }

    /**
     * WO-DEBT-6 S3: updating-phase opener of {@link #completeUserTask} (WO-C8-28).
     * Verbatim block except mechanical return plumbing (the phase-open return stops
     * the whole completion, hence boolean). Returns true when handled.
     */
    private boolean openUpdatingPhaseOnVariables(UUID userTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-28: updating-listener phase — read only for elements that declare
        // updating listeners, so the common path never touches the new state. Opens
        // ONLY on a real variable write (complete WITH variables): a complete without
        // variables is not an update, so it skips straight to completing/immediate
        // below. There is no standalone task-variables endpoint (adding a public REST
        // signature is G-C stop-list), so complete-with-variables is the only entry.
        List<ListenerModel> updatingListeners = elementSupport.userTaskUpdatingListeners(bpmnElement);
        if (!updatingListeners.isEmpty() && variables != null && !variables.isEmpty()) {
            Integer pendingUpdating = dbService.getPendingUpdatingListenerIndex(userTaskId);
            if (pendingUpdating == null) {
                // First complete-with-variables → open the phase. Variables go FIRST
                // and durably (same reason as completing): if a listener fails and the
                // phase waits for incident resolve, the caller's variables must already
                // be in the instance.
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingUpdatingListenerIndex(userTaskId, 0);
                dbService.setUpdatingListenerRetriesRemaining(userTaskId,
                    elementSupport.listenerBudget(updatingListeners.get(0)));
                serviceTaskEnqueueService.enqueueAfterCommit(userTaskId);
                log.info("{}/{}: Completing user task, opening updating-listener phase of {}: {}/{}",
                    processInstanceId, token, activity.getBpmnElementId(), userTaskId, activity.getBpmnElementId());
                return true;
            }
            // Phase already open: same 409 discipline as completing (same exception
            // class — the REST catch maps by class; the message names this phase).
            // deny is deferred (criterion 5): no deny channel exists anywhere, so a
            // repeat complete can only wait, never cancel the update.
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already updating"
                    + " (updating listener " + pendingUpdating + " in flight) — wait for it to finish");
        }
        return false;
    }

    /**
     * WO-DEBT-6 S3: completing-phase opener of {@link #completeUserTask} (WO-C8-24).
     * Verbatim block except mechanical return plumbing (the phase-open return stops
     * the whole completion, hence boolean). Returns true when handled.
     */
    private boolean openCompletingPhase(UUID userTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, Activity activity) {
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
                return true;
            }
            // Phase already open: a repeat complete is a client conflict (409), never a
            // silent re-completion and never a 500 (WO step 5; closes the R1-review defect
            // class on this path — mid-phase REST used to fall into a 500).
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already completing"
                    + " (completing listener " + pendingCompleting + " in flight) — wait for it to finish");
        }
        return false;
    }

    /**
     * WO-C8-28: phase-aware assignment (assign-API). Without assigning listeners the
     * call is byte-identical to {@code dbService.assignUserTask} (same guards, routed
     * there directly). With listeners the assignment parks in {@code pendingAssignee}
     * and an assigning phase runs first; a repeat assign while any listener phase is
     * open is a client conflict (409, same class as completing — the REST catch maps
     * by class, the message names the open phase). deny is deferred (criterion 5):
     * no deny channel exists anywhere in the codebase, so a parked assignment can
     * only wait for its listeners, never be vetoed through this path.
     */
    public void assignUserTask(UUID taskId, String assignee) {
        Activity activity = elementSupport.lockAndReload(taskId);
        BpmnElementModel bpmnElement = bpmnElementOf(activity);
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (assigningListeners.isEmpty()
            || (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS)) {
            dbService.assignUserTask(taskId, assignee);
            return;
        }
        String openPhase = openListenerPhaseName(taskId);
        if (openPhase != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already " + openPhase
                    + " — wait for it to finish");
        }
        dbService.setPendingAssignee(taskId, assignee);
        dbService.setPendingAssigningListenerIndex(taskId, 0);
        dbService.setAssigningListenerRetriesRemaining(taskId,
            elementSupport.listenerBudget(assigningListeners.get(0)));
        serviceTaskEnqueueService.enqueueAfterCommit(taskId);
        log.info("{}/{}: Assigning user task, opening assigning-listener phase of {}: {}/{}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), taskId,
            activity.getBpmnElementId());
    }

    /**
     * WO-C8-28: phase-aware claim (Tasklist assignment). Same shape as
     * {@link #assignUserTask}: the CAS property (claim wins only on an unassigned
     * task) is enforced by the REST pre-check plus the 409 below — every runtime
     * assignee writer funnels through these phase checks under the instance lock,
     * so nothing can slip an assignment in between check and park. The resume tail
     * applies the parked assignee with the plain write (already serialized).
     */
    public void claimUserTask(UUID taskId, String assignee) {
        Activity activity = elementSupport.lockAndReload(taskId);
        BpmnElementModel bpmnElement = bpmnElementOf(activity);
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (assigningListeners.isEmpty()
            || (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS)) {
            dbService.claimUserTask(taskId, assignee);
            return;
        }
        String openPhase = openListenerPhaseName(taskId);
        if (openPhase != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already " + openPhase
                    + " — wait for it to finish");
        }
        dbService.setPendingAssignee(taskId, assignee);
        dbService.setPendingAssigningListenerIndex(taskId, 0);
        dbService.setAssigningListenerRetriesRemaining(taskId,
            elementSupport.listenerBudget(assigningListeners.get(0)));
        serviceTaskEnqueueService.enqueueAfterCommit(taskId);
        log.info("{}/{}: Claiming user task, opening assigning-listener phase of {}: {}/{}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), taskId,
            activity.getBpmnElementId());
    }

    /**
     * WO-C8-28: name of the in-flight listener phase on an activity ("assigning" /
     * "updating" / "completing" / "canceling"), or null when none is open. A creating
     * phase cannot be open wherever a task row exists (it closes by creating the row),
     * so it is not checked here — its callers 404 on the missing row first.
     */
    private String openListenerPhaseName(UUID activityId) {
        if (dbService.getPendingAssigningListenerIndex(activityId) != null) {
            return "assigning";
        }
        if (dbService.getPendingUpdatingListenerIndex(activityId) != null) {
            return "updating";
        }
        if (dbService.getPendingCompletingListenerIndex(activityId) != null) {
            return "completing";
        }
        if (dbService.getPendingCancelingListenerIndex(activityId) != null) {
            return "canceling";
        }
        return null;
    }

    private BpmnElementModel bpmnElementOf(Activity activity) {
        ProcessInstance processInstance = dbService.getProcessInstance(activity.getProcessInstanceId());
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        return bpmn.getElement(activity.getBpmnElementId());
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
        if (resumeElementListenerPhase(serviceTaskId, variables, executor)) {
            return;
        }
        Activity activity = elementSupport.lockAndReload(serviceTaskId);
        if (!isCompletionAllowed(serviceTaskId, activity)) {
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        if (handleCreatingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleCompletingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor)) {
            return;
        }

        if (handleAssigningListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleUpdatingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity, executor)) {
            return;
        }

        if (handleCancelingListeners(serviceTaskId, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor)) {
            return;
        }

        if (rejectPhaseOnlyCompletion(serviceTaskId, bpmnElement)) {
            return;
        }

        if (handleStartListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleEndListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }
        finishServiceTaskCompletion(serviceTaskId, variables, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor);
    }

    /**
     * WO-DEBT-6 S1: status guard of {@link #completeServiceTask} (WO-C8-28).
     * Extracted byte-identical except mechanical return plumbing. Returns true when the completion may proceed.
     */
    private boolean isCompletionAllowed(UUID serviceTaskId, Activity activity) {
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // WO-C8-28: a CANCELLED activity with an open canceling phase is NOT done —
            // its listener completions must reach the canceling branch below (the phase
            // defers the cancellation tail). The extra read runs only for non-active
            // statuses, so the hot CREATED/IN_PROGRESS path never touches the new state.
            if (activity.getStatus() != ActivityStatus.CANCELLED
                || dbService.getPendingCancelingListenerIndex(serviceTaskId) == null) {
                // only an active task may complete. Ignore anything else to avoid advancing the token twice:
                // a redelivered/late RabbitMQ completion (broker is at-least-once), a boundary-timer
                // interruption (CANCELLED), an already-COMPLETED task, or a task parked on an incident
                // (ERROR) that was superseded by incident-resolve re-execution.
                log.info("Ignoring completion of service task {} in status {}", serviceTaskId, activity.getStatus());
                return false;
            }
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: element-listener phase head of {@link #completeServiceTask} (WO-C8-25).
     * Extracted byte-identical except mechanical return plumbing. Returns true when routed.
     */
    private boolean resumeElementListenerPhase(UUID serviceTaskId, List<ProcessVariable> variables,
            TokenExecutor executor) {
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below,
        // byte-identical (one indexed PK read extra on the completion path).
        Optional<ElementListenerPhaseService.Resume> phaseResume =
            elementListenerPhaseService.completePhaseListener(serviceTaskId, variables);
        if (phaseResume.isPresent()) {
            ElementListenerPhaseService.Resume resume = phaseResume.get();
            if (resume.finished()) {
                // No re-entry guard needed: the finished phase is marked done BEFORE this
                // call, so the park-check below finds the done marker and proceeds to the
                // handler instead of re-opening (a loop is structurally impossible).
                executor.execute(resume.processInstanceId(), resume.tokenId(), resume.bpmnElementId());
            }
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: creating-listener dispatcher of {@link #completeServiceTask}.
     * New seam (leaves hold the verbatim blocks); conditions/comments moved unchanged. Returns true when handled.
     */
    private boolean handleCreatingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-21r2: creating-listener completion — the phase index lives on the ACTIVITY
        // row (no user_tasks row exists until the task is really created). A completion means
        // "this listener finished": advance to the next listener (with its own retry budget),
        // or create the task after the last one — WITHOUT touching the service-task tail below.
        List<ListenerModel> creatingListeners = elementSupport.userTaskCreatingListeners(bpmnElement);
        if (!creatingListeners.isEmpty()) {
            Integer pendingCreating = dbService.getPendingCreatingListenerIndex(serviceTaskId);
            if (pendingCreating != null) {
                if (pendingCreating >= 0 && pendingCreating < creatingListeners.size()) {
                    return advanceCreatingListener(serviceTaskId, variables, processInstanceId, tokenId,
                        bpmnElement, activity, pendingCreating, creatingListeners);
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight, phase MEANT open):
                // fail-open into task creation rather than stranding (mirror of the C8-11
                // fail-open below). A null index is NOT this case — see below.
                return failOpenCreatingListener(serviceTaskId, processInstanceId, tokenId, bpmnElement, activity);
            }
            // Null index = NO creating phase open: this completion is not a creating-listener
            // completion (e.g. a completing-listener job on an element declaring both kinds) —
            // fall through so the completing branch below sees it. WO-C8-24: the old code
            // fail-opened here and re-created the task on every later completion.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: in-range creating-listener step of {@link #completeServiceTask}.
     * Split from {@link #handleCreatingListeners}; verbatim block, mechanical plumbing. Returns true.
     */
    private boolean advanceCreatingListener(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity,
            Integer pendingCreating, List<ListenerModel> creatingListeners) {
        dbService.setVariables(processInstanceId, variables);
        if (pendingCreating + 1 < creatingListeners.size()) {
            dbService.setPendingCreatingListenerIndex(serviceTaskId, pendingCreating + 1);
            dbService.setCreatingListenerRetriesRemaining(serviceTaskId,
                elementSupport.listenerBudget(creatingListeners.get(pendingCreating + 1)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Completing creating listener {} of {}: {}/{}", processInstanceId, tokenId,
                pendingCreating, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return true;
        }
        dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
        dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
        // WO-C8-30: broken priorityDefinition halts here (incident, no row) —
        // same guard as the immediate path above.
        if (!userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement)) {
            return true;
        }
        userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
        log.info("{}/{}: Last creating listener done, task created: {}/{}", processInstanceId, tokenId,
            serviceTaskId, activity.getBpmnElementId());
        // WO-C8-28: creating runs first; a parked assignment opens its own
        // phase now (deterministic order, WO test 8) instead of finishing
        // activation while an assigning transition is due.
        if (userTaskHandler.openAssigningPhaseAfterCreation(processInstanceId, serviceTaskId, bpmnElement)) {
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Creating done, opening assigning-listener phase: {}/{}",
                processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: out-of-bounds creating-listener tail of {@link #completeServiceTask}.
     * Split from {@link #handleCreatingListeners}; verbatim block, mechanical plumbing. Returns true.
     */
    private boolean failOpenCreatingListener(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnElementModel bpmnElement, Activity activity) {
        dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
        dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
        // WO-C8-30: same halt-on-broken-priority guard as the normal tail above.
        if (!userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement)) {
            return true;
        }
        userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
        log.info("{}/{}: Out-of-bounds creating listener index, task created fail-open: {}/{}",
            processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        // WO-C8-28: same assigning hook as the normal tail above (the row was
        // just written by the same body, so the parked-assignee condition holds
        // identically) — a corrupt creating index must not swallow a due
        // assigning transition.
        if (userTaskHandler.openAssigningPhaseAfterCreation(processInstanceId, serviceTaskId, bpmnElement)) {
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Fail-open creating done, opening assigning-listener phase: {}/{}",
                processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: completing-listener phase of {@link #completeServiceTask} (WO-C8-24).
     * Extracted byte-identical except mechanical return plumbing. Returns true when handled.
     */
    private boolean handleCompletingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
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
                        return true;
                    }
                    dbService.setPendingCompletingListenerIndex(serviceTaskId, null);
                    dbService.setCompletingListenerRetriesRemaining(serviceTaskId, null);
                    finishUserTaskCompletion(processInstanceId, tokenId, serviceTaskId, variables, bpmn, bpmnElement, executor);
                    log.info("{}/{}: Last completing listener done, task completed: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return true;
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
                return true;
            }
            // Null index = NO completing phase open: fall through (a creating-listener
            // completion on an element declaring both kinds is handled above; anything else
            // reaching the user-task guard below is spurious and ignored there).
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: assigning-listener phase of {@link #completeServiceTask} (WO-C8-28).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleAssigningListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-28: assigning-listener completion — read only for elements that declare
        // assigning listeners. A completion means "this listener finished": advance to
        // the next listener (with its own retry budget), or apply the parked assignment
        // after the last one. The assignment applies only while the task is still
        // active — a cancellation that won meanwhile leaves the row untouched (the
        // phase is cleared either way so nothing strands).
        List<ListenerModel> assigningListenersRt = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (!assigningListenersRt.isEmpty()) {
            Integer pendingAssigning = dbService.getPendingAssigningListenerIndex(serviceTaskId);
            if (pendingAssigning != null) {
                if (pendingAssigning >= 0 && pendingAssigning < assigningListenersRt.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingAssigning + 1 < assigningListenersRt.size()) {
                        dbService.setPendingAssigningListenerIndex(serviceTaskId, pendingAssigning + 1);
                        dbService.setAssigningListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(assigningListenersRt.get(pendingAssigning + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing assigning listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingAssigning, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return true;
                    }
                    String parkedAssignee = dbService.getPendingAssignee(serviceTaskId);
                    dbService.setPendingAssigningListenerIndex(serviceTaskId, null);
                    dbService.setAssigningListenerRetriesRemaining(serviceTaskId, null);
                    dbService.setPendingAssignee(serviceTaskId, null);
                    if (parkedAssignee != null && (activity.getStatus() == ActivityStatus.CREATED
                        || activity.getStatus() == ActivityStatus.IN_PROGRESS)) {
                        dbService.assignUserTask(serviceTaskId, parkedAssignee);
                    }
                    log.info("{}/{}: Last assigning listener done, assignment applied: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return true;
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight): incident, same
                // as creating/completing — a user task has no real job to fail open into.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds assigning listener index — fix the process model");
                return true;
            }
            // Null index = NO assigning phase open: fall through (a completion for a
            // sibling phase on an element declaring several kinds is handled by its
            // own branch — phases never overlap by construction, see the open sites).
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: updating-listener phase of {@link #completeServiceTask} (WO-C8-28).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleUpdatingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor) {
        // WO-C8-28: updating-listener completion — read only for elements that declare
        // updating listeners. Advance like the sibling phases; after the last listener
        // continue EXACTLY as if complete() was just called with no new variables
        // (re-invocation, not duplication): the user's variables are already durable
        // (applied when the phase opened and at every advance), so an empty call can
        // only open the completing phase or finish — never reopen updating, which
        // requires non-empty variables.
        List<ListenerModel> updatingListenersRt = elementSupport.userTaskUpdatingListeners(bpmnElement);
        if (!updatingListenersRt.isEmpty()) {
            Integer pendingUpdating = dbService.getPendingUpdatingListenerIndex(serviceTaskId);
            if (pendingUpdating != null) {
                if (pendingUpdating >= 0 && pendingUpdating < updatingListenersRt.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingUpdating + 1 < updatingListenersRt.size()) {
                        dbService.setPendingUpdatingListenerIndex(serviceTaskId, pendingUpdating + 1);
                        dbService.setUpdatingListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(updatingListenersRt.get(pendingUpdating + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing updating listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingUpdating, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return true;
                    }
                    dbService.setPendingUpdatingListenerIndex(serviceTaskId, null);
                    dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, null);
                    log.info("{}/{}: Last updating listener done, continuing to completion: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    completeUserTask(serviceTaskId, List.of(), executor);
                    return true;
                }
                // Out-of-bounds/foreign index: incident, same as the sibling phases.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds updating listener index — fix the process model");
                return true;
            }
            // Null index = NO updating phase open: fall through.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: canceling-listener dispatcher of {@link #completeServiceTask}.
     * New seam (in-range step lives in {@link #advanceOrFinishCanceling}); conditions/comments moved unchanged. Returns true when handled.
     */
    private boolean handleCancelingListeners(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor) {
        // WO-C8-28: canceling-listener completion — read only for elements that declare
        // canceling listeners (reachable on CANCELLED activities via the guard exemption
        // above). Advance like the sibling phases; after the last listener run the
        // deferred tail — but only if this was the last open canceling phase in scope
        // (serialized by the process-instance lock held since method entry, so two
        // concurrent closers cannot both see "none open"). Boundary path defers the
        // boundary continuation (per token); process-cancel path defers the
        // process-cancel tail (per instance). Observe-only: no deny branch exists
        // (Camunda: "it's not possible to deny the cancelation").
        List<ListenerModel> cancelingListenersRt = elementSupport.userTaskCancelingListeners(bpmnElement);
        if (!cancelingListenersRt.isEmpty()) {
            Integer pendingCanceling = dbService.getPendingCancelingListenerIndex(serviceTaskId);
            if (pendingCanceling != null) {
                if (pendingCanceling >= 0 && pendingCanceling < cancelingListenersRt.size()) {
                    return advanceOrFinishCanceling(serviceTaskId, processInstanceId, tokenId, bpmn,
                        bpmnElement, activity, executor, pendingCanceling, cancelingListenersRt);
                }
                // Out-of-bounds/foreign index: incident, same as the sibling phases.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds canceling listener index — fix the process model");
                return true;
            }
            // Null index = NO canceling phase open: fall through.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: in-range canceling step of {@link #completeServiceTask} — advance to
     * the next listener, or run the deferred tail after the last one. Verbatim in-range
     * block with mechanical return plumbing. Returns true.
     */
    private boolean advanceOrFinishCanceling(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor, Integer pendingCanceling, List<ListenerModel> cancelingListenersRt) {
        if (pendingCanceling + 1 < cancelingListenersRt.size()) {
            dbService.setPendingCancelingListenerIndex(serviceTaskId, pendingCanceling + 1);
            dbService.setCancelingListenerRetriesRemaining(serviceTaskId,
                elementSupport.listenerBudget(cancelingListenersRt.get(pendingCanceling + 1)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Completing canceling listener {} of {}: {}/{}", processInstanceId, tokenId,
                pendingCanceling, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return true;
        }
        String deferredBoundary = dbService.getPendingCancelBoundaryElementId(serviceTaskId);
        UUID resumeToken = activity.getToken();
        UUID resumePi = activity.getProcessInstanceId();
        dbService.setPendingCancelingListenerIndex(serviceTaskId, null);
        dbService.setCancelingListenerRetriesRemaining(serviceTaskId, null);
        dbService.setPendingCancelBoundaryElementId(serviceTaskId, null);
        log.info("{}/{}: Last canceling listener done, running deferred tail: {}/{}", processInstanceId, tokenId,
            serviceTaskId, activity.getBpmnElementId());
        if (deferredBoundary != null) {
            if (!dbService.hasOpenCancelingListenerPhaseOnToken(resumeToken)) {
                BpmnElementModel boundaryElement = bpmn.getElement(deferredBoundary);
                Token resumeHostToken = dbService.getToken(resumeToken);
                if (resumeHostToken.getPendingBranches() != null && resumeHostToken.getPendingBranches() > 0) {
                    dbService.decrementPendingBranches(resumeToken);
                }
                flowNavigator.proceedToOutgoing(resumePi, resumeToken, bpmn, boundaryElement, executor);
            }
        } else {
            if (!dbService.hasOpenCancelingListenerPhaseInInstance(resumePi)) {
                dbService.deleteTimerJobsByProcessInstanceId(resumePi);
                dbService.deleteMessageSubscriptionsByProcessInstanceId(resumePi);
                dbService.cancelProcessInstance(resumePi);
            }
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: phase-only element guard of {@link #completeServiceTask} (WO-C8-25).
     * Verbatim block, mechanical plumbing. Returns true when ignored.
     */
    private boolean rejectPhaseOnlyCompletion(UUID serviceTaskId, BpmnElementModel bpmnElement) {
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
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: start-listener phase of {@link #completeServiceTask} (WO-C8-11).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleStartListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
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
                return true;
            }
            // Out-of-bounds/foreign index (model redeployed mid-flight): fall through to the
            // normal path below (fail-open, completes and moves the token) rather than stranding.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: end-listener phase of {@link #completeServiceTask} (WO-C8-11b).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleEndListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
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
            return true;
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
                return true;
            }
            dbService.setPendingEndListenerIndex(serviceTaskId, null);
            // Last end listener done → fall through to the real completion tail below
            // (it applies this completion's variables itself).
        }
        // No end phase (or corrupt/foreign end index): fail-open into the normal tail below.
        return false;
    }

    /**
     * WO-DEBT-6 S1: real completion tail of {@link #completeServiceTask}. Verbatim block (void, zero-touch).
     */
    private void finishServiceTaskCompletion(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
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
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result —
     * the counterpart of {@link #completeServiceTask} for the FIRST typed job result in
     * this project. Deliberately NOT routed through the flat tail above (which would
     * proceed the scope's own outgoing): the worker's decision drives activation and
     * finishing here.
     * <p>
     * Staleness is explicit, never silent (unlike the at-least-once-tolerant flat tail):
     * unknown id → {@code NoSuchElementException} (REST 404); inactive scope or token
     * mismatch → 409 CONFLICT (the Zeebe {@code NOT_FOUND}-on-stale-completion analog).
     * A result that both fulfills the condition AND activates elements violates the raw
     * schema ("cannot fulfill both at the same time") → 400.
     */
    public void completeAdHocScopeJob(UUID scopeActivityId,
            com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result, TokenExecutor executor) {
        Activity scope = elementSupport.lockAndReload(scopeActivityId);
        if (scope.getType() != BpmnElementType.AD_HOC_SUB_PROCESS) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "AD_HOC_SCOPE_EXPECTED",
                "Activity " + scopeActivityId + " is not an ad-hoc sub-process scope",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        if (scope.getStatus() != ActivityStatus.CREATED && scope.getStatus() != ActivityStatus.IN_PROGRESS) {
            // Finished/cancelled/errored scope: nobody may decide for it anymore.
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "AD_HOC_JOB_STALE",
                "Ad-hoc scope job " + scopeActivityId + " is stale (scope " + scope.getStatus() + ")",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        boolean fulfilled = Boolean.TRUE.equals(result.getIsCompletionConditionFulfilled());
        List<com.zorrodev.bpm.contract.dto.AdHocActivateElementDTO> activate =
            result.getActivateElements() == null ? List.of() : result.getActivateElements();
        if (fulfilled && !activate.isEmpty()) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "AD_HOC_RESULT_CONTRADICTION",
                "Ad-hoc job result cannot fulfill the completion condition and activate elements at the same time",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        UUID processInstanceId = scope.getProcessInstanceId();
        UUID tokenId = scope.getToken();
        String currentToken = dbService.getVariables(processInstanceId).stream()
            .filter(v -> AdHocJoin.jobTokenVariable(scopeActivityId).equals(v.getName()))
            .findFirst()
            .map(ProcessVariable::getValue)
            .orElse(null);
        if (currentToken == null || result.getJobToken() == null || !currentToken.equals(result.getJobToken())) {
            // Recreated (or internal-mode) scope: this generation is over, explicitly.
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "AD_HOC_JOB_STALE",
                "Ad-hoc scope job " + scopeActivityId + " is stale (job recreated or not job-managed)",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        if (fulfilled) {
            // Worker-owned finish: its cancel flag decides (schema default false — NOT the
            // BPMN attribute default true; different sources, implemented exactly).
            AdHocJoin.ScopeState state =
                AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scopeActivityId);
            dbService.completeServiceTask(scopeActivityId);
            flowNavigator.finishAdHocScope(processInstanceId, tokenId, bpmn, scope,
                state == null ? null : AdHocJoin.joinKey(scopeActivityId, state.batchUuid()), executor,
                Boolean.TRUE.equals(result.getIsCancelRemainingInstances()));
            triggerConditionalEvents(processInstanceId, executor);
            return;
        }
        if (activate.isEmpty()) {
            // Explicit park (documented): worker decided nothing and did not fulfill.
            // Consume the job row so it stops being offered; no recreation, no incident.
            dbService.completeServiceTask(scopeActivityId);
            log.info("{}/{}: Ad-hoc scope job {} completed with empty activation — scope parked",
                processInstanceId, tokenId, scopeActivityId);
            return;
        }
        List<AdHocSubProcessHandler.ActivationRequest> requests = new java.util.ArrayList<>();
        for (com.zorrodev.bpm.contract.dto.AdHocActivateElementDTO item : activate) {
            requests.add(new AdHocSubProcessHandler.ActivationRequest(item.getElementId(),
                item.getVariables() == null ? List.of() : item.getVariables()));
        }
        ExecutionCtx activationCtx =
            new ExecutionCtx(processInstanceId, tokenId, executor, executionContext);
        if (!adHocSubProcessHandler.activateInnerElements(activationCtx, bpmn, scopeElement,
                scopeActivityId, requests, false)) {
            // Invalid element id: incident raised inside (mirrors internal mode), the
            // consumed job is NOT recreated — operator recovery, same posture as entry.
            dbService.completeServiceTask(scopeActivityId);
            return;
        }
        // Consume this generation (row upsert inside issueScopeJob resets it) and offer
        // exactly one current job: at most one VALID generation, older ones go 409.
        AdHocJoin.issueScopeJob(dbService, elementSupport, serviceTaskEnqueueService,
            processInstanceId, scopeActivityId, scopeElement);
    }

    /**
     * Reports a service-task (job) failure from a worker. {@code retries} follows Camunda {@code failJob}:
     * when non-null the retry budget is set to it ({@code 0} raises the incident immediately); when null the
     * budget is decremented by one. While retries remain the job is re-dispatched; when exhausted the activity
     * is marked ERROR and an incident carrying {@code errorMessage} is raised. The token stays parked.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        if (failElementListenerPhase(serviceTaskId, errorMessage, retries)) {
            return;
        }
        Activity activity = elementSupport.lockAndReload(serviceTaskId);
        if (!isFailureProcessable(serviceTaskId, activity)) {
            return;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Service task failed" : errorMessage;
        if (handleUserTaskListenerFailure(serviceTaskId, retries, message, activity)) {
            return;
        }
        failSharedBudget(serviceTaskId, retries, message, activity);
    }

    /**
     * WO-DEBT-6 S2: finished-activity guard of {@link #failServiceTask} — an already
     * finished task ignores the failure (redelivered failure, boundary interruption).
     * Verbatim block, mechanical plumbing. Returns true when the failure may proceed.
     */
    private boolean isFailureProcessable(UUID serviceTaskId, Activity activity) {
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished (redelivered failure, or interrupted by a boundary) — ignore
            log.info("Ignoring failure of service task {} in status {}", serviceTaskId, activity.getStatus());
            return false;
        }
        return true;
    }

    /**
     * WO-DEBT-6 S2: element-listener phase-fail head of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when routed.
     */
    private boolean failElementListenerPhase(UUID serviceTaskId, String errorMessage, Integer retries) {
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below.
        if (elementListenerPhaseService.failPhaseListener(serviceTaskId, errorMessage, retries)) {
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: user-task listener-fail dispatcher of {@link #failServiceTask} — routes
     * to the per-phase budget leaf whose phase is open. New seam (leaves hold the verbatim
     * blocks); conditions moved unchanged, phases checked in original order. Returns true
     * when handled.
     */
    private boolean handleUserTaskListenerFailure(UUID serviceTaskId, Integer retries, String message,
            Activity activity) {
        // WO-C8-21r2: a failing creating-listener job draws from its own durable budget
        // (model value, default 3 — set at phase open/advance; there is no service_tasks
        // row for a user task, so the shared budget path below would orElseThrow).
        // Gated on the activity type first, so the common service-task fail path never
        // pays for the bpmn load below.
        if (activity.getType() == BpmnElementType.USER_TASK) {
            ProcessInstance failPi = dbService.getProcessInstance(activity.getProcessInstanceId());
            BpmnProcessDefinitionModel failBpmn = bpmnService.getProcessDefinitionModelById(failPi.getProcessDefinitionId());
            BpmnElementModel failElement = failBpmn.getElement(activity.getBpmnElementId());
            if (failCreatingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failCompletingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failAssigningListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failUpdatingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failCancelingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: creating-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCreatingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
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
                return true;
            }
            log.info("{}/{}: User task creating listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: completing-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCompletingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
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
                return true;
            }
            log.info("{}/{}: User task completing listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: assigning-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failAssigningListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskAssigningListeners(failElement).isEmpty()
            && dbService.getPendingAssigningListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setAssigningListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getAssigningListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setAssigningListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task assigning listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task assigning listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: updating-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failUpdatingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskUpdatingListeners(failElement).isEmpty()
            && dbService.getPendingUpdatingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getUpdatingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task updating listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task updating listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: canceling-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCancelingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskCancelingListeners(failElement).isEmpty()
            && dbService.getPendingCancelingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setCancelingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getCancelingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setCancelingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task canceling listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task canceling listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: shared retry-budget tail of {@link #failServiceTask} — Camunda failJob
     * semantics, re-dispatch while retries remain, incident when exhausted. Verbatim block
     * (void, zero-touch); called last.
     */
    private void failSharedBudget(UUID serviceTaskId, Integer retries, String message, Activity activity) {
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
     *
     * <p>WO-C8-29: subscriptions declaring {@code zeebe:conditionalFilter} are re-evaluated
     * only when a recorded change matches the filter (see {@code ConditionalFilter});
     * subscriptions without a filter — and passes with no recorded change on this thread
     * (trigger without a preceding write) — evaluate exactly as before.
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
            Map<String, String> changes = executionContext.consumeVariableChanges();
            for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
                BpmnElementModel element = bpmn.getElement(activity.getBpmnElementId());
                if (element == null) {
                    continue;
                }
                if (element.getType() == BpmnElementType.CONDITIONAL_CATCH_EVENT) {
                    if (!conditionalFilterMatches(element, changes)) {
                        continue;
                    }
                    if (eventTrigger.conditionHolds(element, variables)) {
                        log.info("{}: Conditional catch {} satisfied, firing", processInstanceId, element.getId());
                        signal(activity.getId(), List.of(), executor);
                    }
                } else {
                    for (BpmnElementModel boundary : eventTrigger.findConditionalBoundaries(bpmn, element.getId())) {
                        if (!conditionalFilterMatches(boundary, changes)) {
                            continue;
                        }
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

    /**
     * WO-C8-29: consults the resolved {@code zeebe:conditionalFilter} of a conditional
     * element against the changes recorded since the last trigger pass. No filter, or
     * no recorded changes, means evaluate (current behavior — fail-open, never skips).
     */
    private boolean conditionalFilterMatches(BpmnElementModel element, Map<String, String> changes) {
        var filter = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getConditionalFilter)
            .orElse(null);
        return filter == null || filter.matches(changes);
    }
}
