package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        String resolvedAssignee = elementSupport.resolveAssignee(processInstanceId, bpmnElement);
        String resolvedGroups = elementSupport.resolveCandidateGroups(processInstanceId, bpmnElement);
        String resolvedDueDate = elementSupport.resolveDueDate(processInstanceId, bpmnElement);
        String resolvedFollowUpDate = elementSupport.resolveFollowUpDate(processInstanceId, bpmnElement);
        String formKey = bpmnElement.getExtensions() != null && bpmnElement.getExtensions().getUserTaskExtension() != null
            ? bpmnElement.getExtensions().getUserTaskExtension().getFormKey() : null;
        dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, formKey, resolvedDueDate, resolvedFollowUpDate);
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, bpmnElement);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, bpmnElement);
    }
}
