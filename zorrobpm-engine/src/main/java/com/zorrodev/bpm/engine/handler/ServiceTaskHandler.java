package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler for SERVICE_TASK elements.
 * Creates an activity and a service task record, applies I/O mappings, and enqueues the task.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ElementSupport elementSupport;
    private final MultiInstanceExecutor multiInstanceExecutor;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SERVICE_TASK; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        enter(ctx.processInstanceId(), ctx.tokenId(), bpmnElement, ctx.executor());
    }

    /**
     * Enters a service task. Public so that {@link com.zorrodev.bpm.engine.service.ActivityService}
     * can delegate (SendTaskHandler / CallActivityHandler call {@code activityService.enterServiceTask}).
     *
     * @param executor the TokenExecutor for multi-instance delegation; callers from the handler
     *                 chain pass {@code ctx.executor()}, the ActivityService delegate passes {@code this}
     */
    public void enter(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, TokenExecutor executor) {
        if (multiInstanceExecutor.isMultiInstance(bpmnElement)) {
            multiInstanceExecutor.enter(processInstanceId, token, bpmnElement, executor);
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(bpmnElement));
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }
}
