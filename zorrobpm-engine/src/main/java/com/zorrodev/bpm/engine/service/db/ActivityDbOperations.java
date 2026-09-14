package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Activities — операции с активностями.
 * Плумбинг, ноль логики (только сигнатуры, 1:1 из DBService/DBServiceImpl).
 */
public interface ActivityDbOperations {

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnElementModel element);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnFlowModel element);

    void completeActivity(UUID executionId);

    void errorActivity(UUID activityId);

    void cancelActivity(UUID activityId);

    void cancelActiveActivities(UUID processInstanceId);

    void cancelActiveActivitiesForToken(UUID tokenId);

    List<Activity> getActiveActivities(UUID processInstanceId);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    List<Activity> getCompletedActivities(UUID processInstanceId);

    boolean hasActiveActivityOnTokenAndElement(UUID tokenId, String bpmnElementId);

    /** WO-REL-28: COMPLETED activity on (token, element) — guard against re-execution after manual completion. */
    boolean hasCompletedActivityOnTokenAndElement(UUID tokenId, String bpmnElementId);

    Activity getActivity(UUID activityId);

    /**
     * WO-REL-30 (B-3): {@link #getActivity(UUID)} + process-instance lock in ONE
     * {@code SELECT ... FOR UPDATE} ({@code ActivityRepository.findByIdForUpdate}).
     * Same DTO, same lock semantics as the old read-then-lock pair — no race
     * window between read and lock. Requires an active transaction (joins the
     * domain boundary opened by {@code ActivityServiceImpl}/{@code RuntimeServiceImpl}).
     */
    Activity getActivityForUpdate(UUID activityId);

    /**
     * WO-C8-21r2: advance/clear the in-flight creating-listener phase on the activity row
     * (null = no phase). Entity mutation + save (never bulk UPDATE: the dispatcher re-reads
     * the index in the same transaction — a bulk update would leave a stale copy).
     */
    void setPendingCreatingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-21r2: null when no creating-listener phase is in flight for this activity. */
    Integer getPendingCreatingListenerIndex(UUID activityId);

    /** WO-C8-21r2: durable budget of the current creating-listener job (null = unset). */
    void setCreatingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-21r2: remaining retries of the current creating-listener job. */
    Integer getCreatingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-24: advance/clear the in-flight completing-listener phase on the activity row
     * (null = no phase). Entity mutation + save, never bulk UPDATE (same stale-copy reason
     * as the creating pair above — the dispatcher re-reads in the same transaction).
     */
    void setPendingCompletingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-24: null when no completing-listener phase is in flight for this activity. */
    Integer getPendingCompletingListenerIndex(UUID activityId);

    /** WO-C8-24: durable budget of the current completing-listener job (null = unset). */
    void setCompletingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-24: remaining retries of the current completing-listener job. */
    Integer getCompletingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: advance/clear the in-flight assigning-listener phase on the activity
     * row (null = no phase). Entity mutation + save, never bulk UPDATE (same
     * stale-copy reason as the pairs above).
     */
    void setPendingAssigningListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no assigning-listener phase is in flight for this activity. */
    Integer getPendingAssigningListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current assigning-listener job (null = unset). */
    void setAssigningListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current assigning-listener job. */
    Integer getAssigningListenerRetriesRemaining(UUID activityId);

    /** WO-C8-28: parked assignee while the assigning phase runs (null = nothing parked). */
    void setPendingAssignee(UUID activityId, String assignee);

    /** WO-C8-28: parked assignee of an in-flight assigning phase (null when none). */
    String getPendingAssignee(UUID activityId);

    /**
     * WO-C8-28: advance/clear the in-flight updating-listener phase on the activity
     * row (null = no phase). Entity mutation + save, never bulk UPDATE.
     */
    void setPendingUpdatingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no updating-listener phase is in flight for this activity. */
    Integer getPendingUpdatingListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current updating-listener job (null = unset). */
    void setUpdatingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current updating-listener job. */
    Integer getUpdatingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: advance/clear the in-flight canceling-listener phase on the activity
     * row (null = no phase). Entity mutation + save, never bulk UPDATE.
     */
    void setPendingCancelingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no canceling-listener phase is in flight for this activity. */
    Integer getPendingCancelingListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current canceling-listener job (null = unset). */
    void setCancelingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current canceling-listener job. */
    Integer getCancelingListenerRetriesRemaining(UUID activityId);

    /** WO-C8-28: deferred boundary element id (null = process-cancel path). */
    void setPendingCancelBoundaryElementId(UUID activityId, String boundaryElementId);

    /** WO-C8-28: deferred boundary element id (null = process-cancel path). */
    String getPendingCancelBoundaryElementId(UUID activityId);

    /** WO-C8-28: true while any activity on the token has an open canceling phase. */
    boolean hasOpenCancelingListenerPhaseOnToken(UUID tokenId);

    /** WO-C8-28: true while any activity in the instance has an open canceling phase. */
    boolean hasOpenCancelingListenerPhaseInInstance(UUID processInstanceId);
}
