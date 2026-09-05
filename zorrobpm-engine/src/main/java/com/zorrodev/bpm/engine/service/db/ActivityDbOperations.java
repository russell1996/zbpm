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

    Activity getActivity(UUID activityId);

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
}
