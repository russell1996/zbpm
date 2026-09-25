package com.zorrodev.bpm.engine.handler;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Handler for USER_TASK elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTaskHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ElementSupport elementSupport;
    private final MultiInstanceExecutor multiInstanceExecutor;
    private final BoundaryScheduler boundaryScheduler;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.USER_TASK; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID token = ctx.tokenId();

        if (multiInstanceExecutor.isMultiInstance(bpmnElement)) {
            multiInstanceExecutor.enter(processInstanceId, token, bpmnElement, ctx.executor());
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);

        // WO-C8-21r2: elements with creating listeners park a listener job first — the phase
        // index lives on the ACTIVITY row (no user_tasks row exists until the task is really
        // created). Elements without listeners take the pre-existing path below, whose
        // statement order (row → input mappings → boundaries) is preserved exactly.
        List<ListenerModel> creatingListeners = elementSupport.userTaskCreatingListeners(bpmnElement);
        if (creatingListeners.isEmpty()) {
            // WO-C8-30: broken priorityDefinition halts activation here (incident, no
            // row) — the tail below runs only on a created row.
            if (!createTaskRow(processInstanceId, activityId, bpmnElement)) {
                return;
            }
            elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);
            postCreation(processInstanceId, token, activityId, bpmnElement);
            // WO-C8-28: a parked assignment (see createTaskRow) opens its own phase
            // AFTER the row and the boundaries exist — statement order above is
            // preserved exactly; without a parked assignee this is a no-op.
            if (openAssigningPhaseAfterCreation(processInstanceId, activityId, bpmnElement)) {
                serviceTaskEnqueueService.enqueueAfterCommit(activityId);
            }
        } else {
            elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);
            dbService.setPendingCreatingListenerIndex(activityId, 0);
            dbService.setCreatingListenerRetriesRemaining(activityId,
                elementSupport.listenerBudget(creatingListeners.get(0)));
            log.info("{}/{}: Entering {} with {} creating listener(s), phase opened: {}/{}",
                processInstanceId, token, bpmnElement.getType(), creatingListeners.size(), activityId, bpmnElement.getId());
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
    }

    /**
     * Resolves the task fields and writes the task row. The ONLY row-writing body — the
     * immediate path above and the phased path (via {@link CompletionService}) both call
     * it, so the two cannot diverge.
     *
     * <p>Returns false when activation must halt: WO-C8-30 broken
     * {@code priorityDefinition} raises an incident (activity ERROR, no task row) instead
     * of a silent default. Callers must skip their tail on false.
     *
     * <p>Public so that {@link CompletionService} can run it when the last creating
     * listener completes (precedent: {@code ServiceTaskHandler.enter} is public for
     * {@code ActivityService} delegation).
     */
    public boolean createTaskRow(UUID processInstanceId, UUID activityId, BpmnElementModel bpmnElement) {
        String resolvedAssignee = elementSupport.resolveAssignee(processInstanceId, bpmnElement);
        String resolvedGroups = elementSupport.resolveCandidateGroups(processInstanceId, bpmnElement);
        String resolvedDueDate = elementSupport.resolveDueDate(processInstanceId, bpmnElement);
        String resolvedFollowUpDate = elementSupport.resolveFollowUpDate(processInstanceId, bpmnElement);
        String formKey = bpmnElement.getExtensions() != null && bpmnElement.getExtensions().getUserTaskExtension() != null
            ? bpmnElement.getExtensions().getUserTaskExtension().getFormKey() : null;
        // WO-C8-22 (merge resolution): linked-form id rides its own field into the row
        // (never into formKey) — resolved here at creation time in BOTH paths (immediate
        // and phase-end), since finishUserTaskCreation is gone with the marker row.
        String formId = bpmnElement.getExtensions() != null && bpmnElement.getExtensions().getUserTaskExtension() != null
            ? bpmnElement.getExtensions().getUserTaskExtension().getFormId() : null;
        // WO-C8-23: binding rides alongside (latest/absent keep the old resolve path).
        String bindingType = bpmnElement.getExtensions() != null && bpmnElement.getExtensions().getUserTaskExtension() != null
            ? bpmnElement.getExtensions().getUserTaskExtension().getBindingType() : null;
        // WO-C8-28: when assigning listeners are declared and the model assigns someone,
        // the assignment parks (row assignee NULL + pendingAssignee column) until the
        // assigning phase runs it. Without listeners — or with no model assignee — the
        // write below is byte-identical to before (resolvedAssignee straight into the row).
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        String parkedAssignee = (!assigningListeners.isEmpty() && resolvedAssignee != null && !resolvedAssignee.isBlank())
            ? resolvedAssignee : null;
        // WO-C8-30: priority resolves here, in BOTH paths at once (see javadoc above).
        // Broken/out-of-range expression halts activation with an incident (activity
        // ERROR, no row) — never a silent default. Absent attribute → docs default 50.
        final int resolvedPriority;
        try {
            resolvedPriority = elementSupport.resolveUserTaskPriorityOrThrow(processInstanceId, bpmnElement);
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: временная перегрузка пула — не битый priority.
            // Проброс до 503-хендлера (как главный путь ActivityService),
            // битый priorityDefinition по-прежнему идёт в инцидент ниже.
            throw e;
        } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
            log.warn("{}/{}: {}", processInstanceId, activityId, e.getMessage());
            dbService.errorActivity(activityId);
            dbService.createIncident(activityId, e.getMessage());
            return false;
        }
        dbService.createUserTask(activityId, parkedAssignee != null ? null : resolvedAssignee, resolvedGroups, formKey, formId, bindingType, resolvedDueDate, resolvedFollowUpDate, resolvedPriority);
        if (parkedAssignee != null) {
            dbService.setPendingAssignee(activityId, parkedAssignee);
        }
        return true;
    }

    /**
     * WO-C8-28: opens the assigning phase for a freshly created task whose assignment
     * parked in {@link #createTaskRow} (model assignee + assigning listeners). Returns
     * true when opened (caller enqueues); false is a no-op — same condition as the
     * parking above, so an opened phase always has a parked assignee and vice versa.
     * Called after {@code postCreation} so boundaries exist before listeners run.
     *
     * <p>Public so that {@link CompletionService} can run it when the last creating
     * listener completes (creating runs first — deterministic order, WO test 8).
     */
    public boolean openAssigningPhaseAfterCreation(UUID processInstanceId, UUID activityId, BpmnElementModel bpmnElement) {
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (assigningListeners.isEmpty() || dbService.getPendingAssignee(activityId) == null) {
            return false;
        }
        dbService.setPendingAssigningListenerIndex(activityId, 0);
        dbService.setAssigningListenerRetriesRemaining(activityId,
            elementSupport.listenerBudget(assigningListeners.get(0)));
        log.info("{}/{}: Task created with parked assignment, opening assigning-listener phase of {}: {}/{}",
            processInstanceId, activityId, bpmnElement.getType(), activityId, bpmnElement.getId());
        return true;
    }

    /**
     * Runs the post-row creation tail (log + boundary schedules). Called by the immediate
     * path above and by {@link CompletionService} after {@link #createTaskRow} — one body,
     * so the schedule calls cannot diverge between the paths.
     */
    public void postCreation(UUID processInstanceId, UUID token, UUID activityId, BpmnElementModel bpmnElement) {
        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, bpmnElement);
    }
}
