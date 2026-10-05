package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
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
    private final ElementSupport elementSupport;

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

        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getReference)
            .orElse(null);
        // WO-C8-34 (CR-06 + CR-07): the thrower is scope-confined from here on —
        // candidates are completed activities of THIS scope only (CancelEndHandler
        // pattern), with the pre-existing activityRef filter applied on top.
        Token throwToken = dbService.findToken(tokenId).orElse(null);
        UUID scopeActivityId = throwToken == null ? null : throwToken.getScopeActivityId();
        log.info("{}/{}: Compensation throw {} at {} (target {})", processInstanceId, tokenId, bpmnElement.getId(), activityId, activityRef == null ? "all" : activityRef);

        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (scopeActivityId != null) {
            targets = elementSupport.filterActivitiesInScope(processInstanceId, targets, scopeActivityId);
        }
        if (activityRef != null) {
            String ref = activityRef;
            targets = targets.stream().filter(a -> ref.equals(a.getBpmnElementId())).toList();
        }
        runCompensation(processInstanceId, tokenId, bpmn, targets, executor);

        // WO-C8-34 (CR-06): waitForCompletion (BPMN default true) — the throw
        // activity stays IN_PROGRESS until every launched handler really
        // completes; the outgoing continuation runs only then. Explicit
        // waitForCompletion=false keeps the legacy fire-and-continue.
        boolean wait = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getWaitForCompletion)
            .orElse(Boolean.TRUE);
        if (wait) {
            // IN_PROGRESS = parked: resume (from the service/user completion
            // tails) completes it when the last launched handler finishes.
            // With zero launched handlers there is nothing to wait for —
            // complete immediately (old path, no leak).
            if (!havePendingHandlers(processInstanceId, activityId, bpmn, targets)) {
                dbService.completeActivity(activityId);
                flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
            }
            return;
        }
        dbService.completeActivity(activityId);
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
    }

    /**
     * WO-C8-34 (CR-06): did this throw launch any compensation handler that is
     * still unfinished? For each launched handler the LATEST row (by completedAt,
     * then by id for same-millisecond ties) decides: COMPLETED latest → done;
     * anything else latest (CREATED/IN_PROGRESS row, or no row at all) →
     * pending. Latest-row-wins closes the same-transaction snapshot hole
     * (a just-completed handler whose bulk UPDATE is invisible to the
     * repeatable-read snapshot still shows its CREATED row — but the COMPLETED
     * row has a NEWER id, and exactly one of them is latest).
     */
    private boolean havePendingHandlers(UUID processInstanceId, UUID throwActivityId,
            BpmnProcessDefinitionModel bpmn, List<Activity> targets) {
        Map<String, BpmnElementModel> boundaryIndex = indexCompensationBoundaries(bpmn);
        for (Activity target : targets) {
            BpmnElementModel boundary = boundaryIndex.get(target.getBpmnElementId());
            if (boundary == null) {
                continue;
            }
            String handlerId = Optional.ofNullable(boundary.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getCompensationHandlerId)
                .orElse(null);
            if (handlerId == null || bpmn.getElement(handlerId) == null) {
                continue;
            }
            if (isHandlerPending(processInstanceId, handlerId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * WO-C8-34 (CR-06): latest-row-wins for one handler element. Compares the
     * newest COMPLETED row against the newest ACTIVE (CREATED/IN_PROGRESS) row
     * by completedAt (fallback: createdAt for active rows, which never have
     * completedAt), then by id for same-millisecond ties. Pending unless a
     * COMPLETED row exists AND no ACTIVE row is newer than it. Same-millisecond
     * completes-then-recreates ties are vanishingly rare and fail CLOSED
     * (parked, healed by the next resume) — never silently continued.
     */
    private boolean isHandlerPending(UUID processInstanceId, String handlerId) {
        java.time.Instant newestCompleted = null;
        java.util.UUID newestCompletedId = null;
        for (Activity a : dbService.getCompletedActivities(processInstanceId)) {
            if (!handlerId.equals(a.getBpmnElementId())) {
                continue;
            }
            if (newestCompleted == null || compareActivityRecency(a.getCompletedAt(), a.getId(),
                    newestCompleted, newestCompletedId) > 0) {
                newestCompleted = a.getCompletedAt();
                newestCompletedId = a.getId();
            }
        }
        java.time.Instant newestActive = null;
        java.util.UUID newestActiveId = null;
        for (Activity a : dbService.getActiveActivities(processInstanceId)) {
            if (!handlerId.equals(a.getBpmnElementId())) {
                continue;
            }
            if (newestActive == null || compareActivityRecency(a.getCreatedAt(), a.getId(),
                    newestActive, newestActiveId) > 0) {
                newestActive = a.getCreatedAt();
                newestActiveId = a.getId();
            }
        }
        if (newestCompleted == null) {
            // launched (boundary maps it) but never finished — still pending.
            return true;
        }
        if (newestActive == null) {
            return false;
        }
        // both exist: pending only if the active row is strictly newer.
        return compareActivityRecency(newestActive, newestActiveId, newestCompleted, newestCompletedId) > 0;
    }

    private static int compareActivityRecency(java.time.Instant t1, java.util.UUID id1,
            java.time.Instant t2, java.util.UUID id2) {
        if (t1 != null && t2 != null) {
            int c = t1.compareTo(t2);
            if (c != 0) {
                return c;
            }
        } else if (t1 != null) {
            return 1;
        } else if (t2 != null) {
            return -1;
        }
        if (id1 != null && id2 != null) {
            return id1.compareTo(id2);
        }
        return 0;
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
