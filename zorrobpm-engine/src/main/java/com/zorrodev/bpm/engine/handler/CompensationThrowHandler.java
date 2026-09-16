package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for COMPENSATION_THROW_EVENT elements.
 * Completes its own activity, then runs the compensation handler of every completed
 * compensation-bounded activity in the instance, in reverse order, before continuing
 * down its outgoing flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationThrowHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.COMPENSATION_THROW_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        processCompensationThrow(ctx.processInstanceId(), ctx.tokenId(), bpmn, bpmnElement, ctx.executor());
    }

    private void processCompensationThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, TokenExecutor executor) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getReference)
            .orElse(null);
        log.info("{}/{}: Compensation throw {} at {} (target {})", processInstanceId, tokenId, bpmnElement.getId(), activityId, activityRef == null ? "all" : activityRef);

        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (activityRef != null) {
            targets = targets.stream().filter(a -> activityRef.equals(a.getBpmnElementId())).toList();
        }
        runCompensation(processInstanceId, tokenId, bpmn, targets, executor);

        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
    }

    /**
     * Runs the compensation handler of every candidate activity that has a compensation boundary, in
     * reverse order of COMPLETION (latest completed first — the reverse of the order the work
     * finished in). Each handler runs synchronously on {@code runToken}. A failing
     * compensation is recorded as an incident and does NOT stop the remaining
     * ones (WO-REL-40 B-7): one broken handler must not strand the rest
     * uncompensated. Shared by the compensation throw event and transaction
     * cancellation.
     */
    public void runCompensation(UUID processInstanceId, UUID runToken, BpmnProcessDefinitionModel bpmn, List<Activity> candidates, TokenExecutor executor) {
        List<Activity> completed = new ArrayList<>(candidates);
        // WO-REL-40 (B-7): reverse COMPLETION order (completedAt descending,
        // nulls last — an activity without a completion timestamp sorts after
        // completed ones), not creation order: two tasks created in order A,B
        // can finish B,A. Note: nullsLast(reverseOrder()), NOT
        // nullsLast(naturalOrder()).reversed() — reversed() flips the null
        // placement too and would sort timestamp-less activities FIRST.
        completed.sort(Comparator.comparing(Activity::getCompletedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, BpmnElementModel> boundaryIndex = indexCompensationBoundaries(bpmn);
        for (Activity activity : completed) {
            BpmnElementModel boundary = boundaryIndex.get(activity.getBpmnElementId());
            if (boundary == null) {
                continue;
            }
            String handlerId = Optional.ofNullable(boundary.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getCompensationHandlerId)
                .orElse(null);
            BpmnElementModel handler = handlerId == null ? null : bpmn.getElement(handlerId);
            if (handler == null) {
                continue;
            }
            log.info("{}/{}: Compensating {} via handler {}", processInstanceId, runToken, activity.getBpmnElementId(), handlerId);
            try {
                executor.execute(processInstanceId, runToken, bpmn, handler);
            } catch (RuntimeException e) {
                // WO-REL-40 (B-7): isolate — park the failed compensation as an
                // incident and continue with the rest, never abort the loop.
                log.error("{}/{}: Compensation handler {} for {} failed, continuing with the rest",
                    processInstanceId, runToken, handlerId, activity.getBpmnElementId(), e);
                UUID activityId = dbService.createActivity(processInstanceId, runToken, handler);
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId,
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }
    }

    /**
     * WO-REL-40 (B-7): one pass over the model builds host-id → boundary; the
     * loop above is then O(1) per activity instead of O(N) per lookup.
     */
    private Map<String, BpmnElementModel> indexCompensationBoundaries(BpmnProcessDefinitionModel bpmn) {
        Map<String, BpmnElementModel> index = new HashMap<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (attached != null) {
                index.putIfAbsent(attached, element);
            }
        }
        return index;
    }

    /** Finds the compensation boundary attached to {@code hostId}, or null if none. */
    private BpmnElementModel findCompensationBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        return indexCompensationBoundaries(bpmn).get(hostId);
    }
}
