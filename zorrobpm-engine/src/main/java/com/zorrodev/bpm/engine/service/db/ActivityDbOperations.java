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
}
