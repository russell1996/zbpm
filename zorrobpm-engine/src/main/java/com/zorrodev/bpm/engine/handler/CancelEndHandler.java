package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for CANCEL_END_EVENT elements (inside a transaction).
 * Compensates the transaction's completed activities, cancels the scope,
 * and continues from the transaction's cancel boundary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelEndHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ActivityService activityService;
    private final CompensationThrowHandler compensationThrowHandler;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.CANCEL_END_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        processCancelEnd(ctx.processInstanceId(), ctx.tokenId(), bpmn, bpmnElement, ctx.executor());
    }

    private void processCancelEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, TokenExecutor executor) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        Token endToken = dbService.getToken(tokenId);
        if (endToken == null || endToken.getScopeActivityId() == null) {
            log.info("{}/{}: Cancel end {} outside a transaction scope, ending branch", processInstanceId, tokenId, bpmnElement.getId());
            activityService.finishBranch(processInstanceId, tokenId, bpmn);
            return;
        }

        UUID scopeActivityId = endToken.getScopeActivityId();
        Activity scope = dbService.getActivity(scopeActivityId);
        BpmnElementModel transaction = bpmn.getElement(scope.getBpmnElementId());
        UUID parentToken = endToken.getParentId();
        log.info("{}/{}: Cancel end {} cancelling transaction {}", processInstanceId, tokenId, bpmnElement.getId(), transaction.getId());

        // compensate the transaction's completed activities (those carried by this scope token)
        List<Activity> scopeCompleted = dbService.getCompletedActivities(processInstanceId).stream()
            .filter(a -> tokenId.equals(a.getToken()))
            .toList();
        compensationThrowHandler.runCompensation(processInstanceId, tokenId, bpmn, scopeCompleted, executor);

        // cancel the transaction scope, then continue from the (interrupting) cancel boundary
        dbService.cancelActiveActivitiesForToken(tokenId);
        dbService.cancelActivity(scopeActivityId);

        BpmnElementModel cancelBoundary = findCancelBoundary(bpmn, transaction.getId());
        if (cancelBoundary != null) {
            flowNavigator.proceedToOutgoing(processInstanceId, parentToken, bpmn, cancelBoundary, executor);
        } else {
            log.warn("{}/{}: Transaction {} cancelled but has no cancel boundary", processInstanceId, tokenId, transaction.getId());
        }
    }

    /** Finds the cancel boundary attached to {@code hostId} (a transaction), or null if none. */
    private BpmnElementModel findCancelBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CANCEL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                return element;
            }
        }
        return null;
    }
}
