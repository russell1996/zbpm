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
            createTaskRow(processInstanceId, activityId, bpmnElement);
            elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);
            postCreation(processInstanceId, token, activityId, bpmnElement);
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
     * <p>Public so that {@link CompletionService} can run it when the last creating
     * listener completes (precedent: {@code ServiceTaskHandler.enter} is public for
     * {@code ActivityService} delegation).
     */
    public void createTaskRow(UUID processInstanceId, UUID activityId, BpmnElementModel bpmnElement) {
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
        dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, formKey, formId, resolvedDueDate, resolvedFollowUpDate);
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
