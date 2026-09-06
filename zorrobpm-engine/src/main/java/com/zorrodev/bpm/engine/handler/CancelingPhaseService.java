package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * WO-C8-28: opener side of the canceling-listener phase.
 *
 * <p>Split out of {@link CompletionService} ON PURPOSE: the boundary-cancel flow lives
 * in {@link EventTrigger}, which {@link CompletionService} already depends on — putting
 * the opener there would close a constructor-injection cycle. This component depends
 * only leaf-ward (DB/bpmn/enqueue/model-readers), so both {@link EventTrigger} and the
 * process-cancel REST path can use it. The resume side stays in
 * {@link CompletionService} next to its sibling phases (it needs the live
 * {@code TokenExecutor}, which only exists there).
 *
 * <p>Observe-only: Camunda does not support deny for canceling ("it's not possible to
 * deny the cancelation"), and no deny channel exists anywhere in this codebase anyway
 * (criterion 5 defers deny for all three events) — so there is no deny branch here
 * by construction, not by omission.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelingPhaseService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ElementSupport elementSupport;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final ActivityRepository activityRepository;

    /**
     * Active user tasks on a token — snapshot BEFORE cancelling (the opener below
     * only ever runs for freshly cancelled activities, so an already-cancelled task
     * whose canceling phase ran and closed is never reopened by a later firing).
     */
    public List<UUID> activeUserTaskIdsOnToken(UUID tokenId) {
        return activityRepository.findByTokenAndStatusIn(
                tokenId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).stream()
            .filter(a -> a.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
            .map(ActivityEntity::getId)
            .toList();
    }

    /**
     * Active user tasks in an instance — snapshot BEFORE cancelling (same reason).
     */
    public List<UUID> activeUserTaskIdsInInstance(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdAndStatusIn(
                processInstanceId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).stream()
            .filter(a -> a.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
            .map(ActivityEntity::getId)
            .toList();
    }

    /**
     * Opens canceling phases for every eligible activity id (interrupting-boundary
     * path). Returns true when at least one phase opened — the caller then defers
     * the boundary continuation until the last one closes.
     */
    public boolean openForActivities(List<UUID> activityIds, String boundaryElementId) {
        boolean opened = false;
        for (UUID activityId : activityIds) {
            if (openForActivity(activityId, boundaryElementId)) {
                opened = true;
            }
        }
        return opened;
    }

    /**
     * Opens canceling phases for a process-cancel snapshot ({@code boundaryElementId}
     * null). Returns true when at least one phase is open afterwards — newly opened,
     * or already open from an earlier cancel whose tail is still deferred (a second
     * cancel while phases run must defer its tail again, not run it): the already-open
     * check spans the whole instance, not just this snapshot.
     */
    public boolean openForInstanceSnapshot(UUID processInstanceId, List<UUID> activityIds) {
        boolean opened = false;
        for (UUID activityId : activityIds) {
            if (openForActivity(activityId, null)) {
                opened = true;
            }
        }
        if (!opened) {
            opened = dbService.hasOpenCancelingListenerPhaseInInstance(processInstanceId);
        }
        return opened;
    }

    /**
     * Opens the canceling phase for one cancelled activity when eligible (declares
     * canceling listeners, no other listener phase open). Superseded parked
     * transitions (assigning/updating/completing) are cleared — cancellation wins
     * over them; a stale creating phase is deliberately left alone (pre-existing
     * edge, V7 — not this WO). Returns true when opened (already-open counts as
     * false: the phase runs once, a second cancel must not re-enqueue its job).
     */
    public boolean openForActivity(UUID activityId, String boundaryElementId) {
        Activity activity = elementSupport.lockAndReload(activityId);
        if (activity.getStatus() != ActivityStatus.CANCELLED) {
            return false;
        }
        if (activity.getType() != com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK) {
            return false;
        }
        BpmnElementModel bpmnElement = bpmnElementOf(activity);
        List<ListenerModel> cancelingListeners = elementSupport.userTaskCancelingListeners(bpmnElement);
        if (cancelingListeners.isEmpty() || anyPhaseOpen(activityId)) {
            return false;
        }
        clearSupersededPhases(activityId);
        dbService.setPendingCancelingListenerIndex(activityId, 0);
        dbService.setCancelingListenerRetriesRemaining(activityId,
            elementSupport.listenerBudget(cancelingListeners.get(0)));
        dbService.setPendingCancelBoundaryElementId(activityId, boundaryElementId);
        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        log.info("{}/{}: Activity cancelled, opening canceling-listener phase of {}: {}/{}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), activityId,
            activity.getBpmnElementId());
        return true;
    }

    private boolean anyPhaseOpen(UUID activityId) {
        return dbService.getPendingAssigningListenerIndex(activityId) != null
            || dbService.getPendingUpdatingListenerIndex(activityId) != null
            || dbService.getPendingCompletingListenerIndex(activityId) != null
            || dbService.getPendingCancelingListenerIndex(activityId) != null
            || dbService.getPendingCreatingListenerIndex(activityId) != null;
    }

    private void clearSupersededPhases(UUID activityId) {
        dbService.setPendingAssigningListenerIndex(activityId, null);
        dbService.setAssigningListenerRetriesRemaining(activityId, null);
        dbService.setPendingAssignee(activityId, null);
        dbService.setPendingUpdatingListenerIndex(activityId, null);
        dbService.setUpdatingListenerRetriesRemaining(activityId, null);
        dbService.setPendingCompletingListenerIndex(activityId, null);
        dbService.setCompletingListenerRetriesRemaining(activityId, null);
    }

    private BpmnElementModel bpmnElementOf(Activity activity) {
        com.zorrodev.bpm.contract.model.ProcessInstance processInstance =
            dbService.getProcessInstance(activity.getProcessInstanceId());
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        return bpmn.getElement(activity.getBpmnElementId());
    }
}
