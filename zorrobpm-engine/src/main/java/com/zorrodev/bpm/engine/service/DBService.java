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

    /** Active (created/in-progress) activities for a specific token and BPMN element. */
    boolean hasActiveActivityOnTokenAndElement(UUID tokenId, String bpmnElementId);

    /** Open (unresolved) incidents linked to any of the given activity IDs. */
    List<com.zorrodev.bpm.contract.dto.Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds);

    /** Close all open incidents linked to the given activity IDs (auto-close stale). */
    void completeIncidentsByActivityIds(List<UUID> activityIds);

    /** Completed activities of an instance — used to compensate them (in reverse completion order). */
    List<Activity> getCompletedActivities(UUID processInstanceId);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    /**
     * Acquires a pessimistic write lock on the process instance for the duration of the current
     * transaction, serialising concurrent execution that touches the same instance.
     */
    void lockProcessInstance(UUID processInstanceId);

    void createServiceTask(UUID activityId);

    /** Creates the service-task job with an explicit retry budget (from {@code zeebe:taskDefinition retries}). */
    void createServiceTask(UUID activityId, int retriesRemaining);

    /** Decrements the service task's retry budget and returns the remaining value. */
    int decrementServiceTaskRetries(UUID serviceTaskId);

    /** Sets the service task's retry budget to an explicit value (Camunda {@code failJob(retries)}). */
    void setServiceTaskRetries(UUID serviceTaskId, int retries);

    void completeServiceTask(UUID serviceTaskId);

    void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey);

    void completeUserTask(UUID serviceTaskId);

    void claimUserTask(UUID taskId, String assignee);

    void unclaimUserTask(UUID taskId);

    void assignUserTask(UUID taskId, String assignee);

    Activity getActivity(UUID activityId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    /** Merged view of the process-instance root scope and a local {@code scopeId} (local shadows root). */
    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    /** Writes variables into a local scope ({@code scopeId == null} writes the process-instance root). */
    void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables);

    /** Drops all variables of a local scope (e.g. an activity's IO-mapping inputs after it completes). */
    void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Token createToken(UUID parentId);

    Token createToken(UUID parentId, UUID scopeActivityId);

    Token getToken(UUID tokenId);

    /**
     * Consumes (deletes) a token that has reached an end event.
     * Called by {@code FlowNavigator.finishBranch} for top-level tokens.
     */
    void deleteToken(UUID tokenId);

    /**
     * WO-ENG-1: Sets the durable pending-branch counter on a token (for parallel/inclusive
     * gateway forks). NULL means linear (no fork); 0 means all consumed.
     */
    void setPendingBranches(UUID tokenId, int count);

    /**
     * WO-ENG-1: Atomic decrement of the pending-branch counter. Returns the new value after
     * decrement. If the token had no counter (null), returns -1 (linear process — caller
     * should complete the instance).
     */
    int decrementPendingBranches(UUID tokenId);

    Integer getMaxProcessDefinitionVersionByKey(String key);

    void completeProcessInstance(UUID processInstanceId);

    void cancelProcessInstance(UUID processInstanceId);

    void deleteTimerJobsByProcessInstanceId(UUID processInstanceId);

    void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId);

    UUID createIncident(UUID activityId, String message);

    Incident getIncident(UUID incidentId);

    void completeIncident(UUID incidentId);

    /** WO-PERF-3: creates a timer job with processInstanceId for retention cleanup. */
    UUID createTimerJob(UUID activityId, java.time.Instant dueAt, String boundaryElementId, Integer remainingCount, String expression, UUID processInstanceId);

    /** Creates a timer job that triggers a timer-started event sub-process when due (no host activity). */
    UUID createEventSubprocessTimerJob(UUID processInstanceId, java.time.Instant dueAt, String eventSubprocessId);

    List<com.zorrodev.bpm.engine.dto.TimerJob> findDueTimerJobs(java.time.Instant now);

    /** L6: SELECT … FOR UPDATE SKIP LOCKED — atomically locks due rows for the calling transaction.
     *  WO-REL-11: batchSize caps the number of rows locked per poll. */
    List<com.zorrodev.bpm.engine.dto.TimerJob> findDueTimerJobsLocked(java.time.Instant now, int batchSize);

    /** Atomically claim a timer job: sets fired=true only if currently false. Returns true if claimed. */
    boolean claimTimerJob(UUID timerJobId);

    /** WO-REL-13: per-job failure bookkeeping (attempts++/last_error) in its own transaction. */
    void recordTimerJobError(UUID timerJobId, String errorMessage);

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

    /** Creates an instance-scoped signal subscription that triggers a signal-started event sub-process. */
    UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId);

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

    /** WO-REL-14: also persists remainingCount, so a bounded repeating cycle can actually be exhausted. */
    void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, java.time.Instant dueAt, Integer remainingCount);

    void deleteTimerStartJobsByKey(String processKey);

    List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobs(java.time.Instant now);

    /** L6: SELECT … FOR UPDATE SKIP LOCKED — atomically locks due rows for the calling transaction.
     *  WO-REL-11: batchSize caps the number of rows locked per poll. */
    List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobsLocked(java.time.Instant now, int batchSize);

    /** Atomically claim a timer start job: sets fired=true only if currently false. Returns true if claimed. */
    boolean claimTimerStartJob(UUID timerStartJobId);

    /** WO-REL-13: per-job failure bookkeeping (attempts++/last_error) in its own transaction. */
    void recordTimerStartJobError(UUID timerStartJobId, String errorMessage);

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
