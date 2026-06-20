package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface DBService {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnElementModel element);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnFlowModel element);

    void completeActivity(UUID executionId);

    void errorActivity(UUID activityId);

    void cancelActivity(UUID activityId);

    void cancelActiveActivities(UUID processInstanceId);

    /** Cancels active (created/in-progress) activities carried by a single token (e.g. an
     *  interrupted subprocess scope). */
    void cancelActiveActivitiesForToken(UUID tokenId);

    /** Active (created/in-progress) activities of an instance — used to re-evaluate conditional events. */
    List<Activity> getActiveActivities(UUID processInstanceId);

    /** Completed activities of an instance — used to compensate them (in reverse completion order). */
    List<Activity> getCompletedActivities(UUID processInstanceId);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    /**
     * Acquires a pessimistic write lock on the process instance for the duration of the current
     * transaction, serialising concurrent execution that touches the same instance.
     */
    void lockProcessInstance(UUID processInstanceId);

    void createServiceTask(UUID activityId);

    void completeServiceTask(UUID serviceTaskId);

    void createUserTask(UUID activityId);

    void completeUserTask(UUID serviceTaskId);

    Activity getActivity(UUID activityId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Token createToken(UUID parentId);

    Token createToken(UUID parentId, UUID scopeActivityId);

    Token getToken(UUID tokenId);

    Integer getMaxProcessDefinitionVersionByKey(String key);

    void completeProcessInstance(UUID processInstanceId);

    UUID createIncident(UUID activityId, String message);

    Incident getIncident(UUID incidentId);

    void completeIncident(UUID incidentId);

    UUID createTimerJob(UUID activityId, java.time.Instant dueAt);

    UUID createTimerJob(UUID activityId, java.time.Instant dueAt, String boundaryElementId);

    List<com.zorrodev.bpm.engine.dto.TimerJob> findDueTimerJobs(java.time.Instant now);

    void markTimerJobFired(UUID timerJobId);

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName);

    /** Creates a message subscription for a message boundary event attached to {@code activityId}. */
    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId);

    /** Creates a message subscription carrying an evaluated correlation-key value (or null). */
    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey);

    /** Creates an instance-scoped message subscription that triggers a message-started event sub-process. */
    UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId);

    List<com.zorrodev.bpm.engine.dto.MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId);

    /** Active subscriptions matching the message name and correlation-key value (targeted delivery). */
    List<com.zorrodev.bpm.engine.dto.MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey);

    void consumeMessageSubscription(UUID subscriptionId);

    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName);

    /** Creates a signal subscription for a signal boundary event attached to {@code activityId}. */
    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId);

    /** All active (unconsumed) subscriptions for {@code signalName}; a signal throw wakes them all. */
    List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName);

    void consumeSignalSubscription(UUID subscriptionId);

    /** Replaces any signal-start subscriptions for {@code processKey} with a fresh one (newer
     *  versions supersede older ones). */
    void createSignalStartSubscription(String processKey, UUID processDefinitionId, String elementId, String signalName);

    void deleteSignalStartSubscriptionsByKey(String processKey);

    /** All signal-start subscriptions for {@code signalName}; a broadcast starts an instance of each. */
    List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> findSignalStartSubscriptions(String signalName);

    /** Replaces any message-start subscriptions for {@code processKey} with a fresh one (newer
     *  versions supersede older ones). */
    void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName);

    /** Removes all message-start subscriptions for a process key (used before re-registering a new version). */
    void deleteMessageStartSubscriptionsByKey(String processKey);

    List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> findMessageStartSubscriptions(String messageName);

    /** Replaces any timer-start jobs for {@code processKey} with a fresh one. */
    void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, java.time.Instant dueAt);

    void deleteTimerStartJobsByKey(String processKey);

    List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobs(java.time.Instant now);

    void markTimerStartJobFired(UUID timerStartJobId);

    /**
     * Records that a branch has arrived at a parallel-gateway join through {@code enteredFlowId}.
     * Idempotent: a repeated arrival for the same incoming flow does not create a duplicate.
     */
    void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId);

    /**
     * Returns the set of incoming flow ids that have so far arrived at the given join.
     */
    java.util.Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId);

    /**
     * Clears all recorded arrivals for the given join, so a later loop through it starts afresh.
     */
    void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId);

    /**
     * Records, for an inclusive-gateway join, how many branches its split activated (the number of
     * arrivals the join must wait for). Stored as a marker row alongside the arrival rows.
     */
    void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount);

    /** Expected arrival count recorded for an inclusive join, or {@code null} if none was recorded. */
    Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId);
}
