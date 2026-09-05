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
        // WO-C8-11: elements with start listeners park a listener job first (pendingListenerIndex=0);
        // the real job is dispatched only after the last listener completes. Elements without
        // listeners take the pre-existing path with a null index (behaviour unchanged).
        List<ListenerModel> startListeners = elementSupport.serviceTaskStartListeners(bpmnElement);
        if (startListeners.isEmpty()) {
            dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(bpmnElement), elementSupport.serviceTaskJob(bpmnElement));
        } else {
            dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(bpmnElement), elementSupport.serviceTaskJob(bpmnElement), 0);
            // WO-C8-21r2: the in-flight listener owns the retry budget (model value, default
            // 3) — not the real job's budget it used to silently consume. Restored for the
            // real job when the last listener completes (see CompletionService).
            dbService.setServiceTaskRetries(activityId, elementSupport.listenerBudget(startListeners.get(0)));
        }
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }
}
