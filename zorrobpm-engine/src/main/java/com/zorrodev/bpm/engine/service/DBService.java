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
import java.util.Optional;
import java.util.UUID;

public interface DBService {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    /**
     * WO-API-1 (API-7): тот же create, но initiator пишется в том же INSERT.
     * Null = без инициатора (старый путь побайтово).
     */
    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
        List<ProcessVariable> variables, String claimedInitiator);

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

    /** Creates the service-task job with an explicit retry budget (from {@code zeebe:taskDefinition retries}) and a stable job id (from {@code zeebe:taskDefinition type}). */
    void createServiceTask(UUID activityId, int retriesRemaining, String job);

    /** WO-C8-11: creates the service task with a listener in flight (index into startListeners). */
    void createServiceTask(UUID activityId, int retriesRemaining, String job, Integer pendingListenerIndex);

    /** WO-C8-11: advances (or clears, with null) the in-flight listener; null = real job path. */
    void setPendingListenerIndex(UUID serviceTaskId, Integer pendingListenerIndex);

    /** WO-C8-11: reads the in-flight listener index; null = normal path. */
    Integer getServiceTaskPendingListenerIndex(UUID serviceTaskId);

    /** WO-C8-11b: advances (or clears, with null) the in-flight end-listener; null = end phase off. */
    void setPendingEndListenerIndex(UUID serviceTaskId, Integer pendingEndListenerIndex);

    /** WO-C8-11b: reads the in-flight end-listener index; null = end phase off. */
    Integer getServiceTaskPendingEndListenerIndex(UUID serviceTaskId);

    /** Decrements the service task's retry budget and returns the remaining value. */
    int decrementServiceTaskRetries(UUID serviceTaskId);

    /** Sets the service task's retry budget to an explicit value (Camunda {@code failJob(retries)}). */
    void setServiceTaskRetries(UUID serviceTaskId, int retries);

    void completeServiceTask(UUID serviceTaskId);

    void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String bindingType, String dueDate, String followUpDate, Integer priority);

    /**
     * WO-C8-21r2: the creating-listener phase index lives on the ACTIVITIES row (no
     * {@code user_tasks} row exists until the task is really created). Null = no phase.
     */
    void setPendingCreatingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-21r2: null when no creating-listener phase is in flight for this activity. */
    Integer getPendingCreatingListenerIndex(UUID activityId);

    /** WO-C8-21r2: durable budget of the current creating-listener job (null = unset). */
    void setCreatingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-21r2: remaining retries of the current creating-listener job. */
    Integer getCreatingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-24: the completing-listener phase index lives on the ACTIVITIES row, mirror
     * of the creating pair above. Null = no phase.
     */
    void setPendingCompletingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-24: null when no completing-listener phase is in flight for this activity. */
    Integer getPendingCompletingListenerIndex(UUID activityId);

    /** WO-C8-24: durable budget of the current completing-listener job (null = unset). */
    void setCompletingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-24: remaining retries of the current completing-listener job. */
    Integer getCompletingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: the assigning-listener phase index lives on the ACTIVITIES row, mirror
     * of the creating/completing pairs above. Null = no phase. The parked assignment
     * itself lives in {@link #setPendingAssignee}.
     */
    void setPendingAssigningListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no assigning-listener phase is in flight for this activity. */
    Integer getPendingAssigningListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current assigning-listener job (null = unset). */
    void setAssigningListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current assigning-listener job. */
    Integer getAssigningListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: assignee parked while the assigning phase runs (model assignee at
     * activation, requested assignee at assign/claim). Null = nothing parked.
     */
    void setPendingAssignee(UUID activityId, String assignee);

    /** WO-C8-28: parked assignee of an in-flight assigning phase (null when none). */
    String getPendingAssignee(UUID activityId);

    /**
     * WO-C8-28: the updating-listener phase index lives on the ACTIVITIES row, mirror
     * of the pairs above. Null = no phase.
     */
    void setPendingUpdatingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no updating-listener phase is in flight for this activity. */
    Integer getPendingUpdatingListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current updating-listener job (null = unset). */
    void setUpdatingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current updating-listener job. */
    Integer getUpdatingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: the canceling-listener phase index lives on the ACTIVITIES row, mirror
     * of the pairs above. Null = no phase. The deferred tail (boundary continuation
     * vs process-cancel tail) is chosen by {@link #getPendingCancelBoundaryElementId}.
     */
    void setPendingCancelingListenerIndex(UUID activityId, Integer index);

    /** WO-C8-28: null when no canceling-listener phase is in flight for this activity. */
    Integer getPendingCancelingListenerIndex(UUID activityId);

    /** WO-C8-28: durable budget of the current canceling-listener job (null = unset). */
    void setCancelingListenerRetriesRemaining(UUID activityId, Integer remaining);

    /** WO-C8-28: remaining retries of the current canceling-listener job. */
    Integer getCancelingListenerRetriesRemaining(UUID activityId);

    /**
     * WO-C8-28: boundary element whose continuation the canceling phase defers.
     * Null = process-cancel path (no boundary involved).
     */
    void setPendingCancelBoundaryElementId(UUID activityId, String boundaryElementId);

    /** WO-C8-28: deferred boundary element id (null = process-cancel path). */
    String getPendingCancelBoundaryElementId(UUID activityId);

    /**
     * WO-C8-28: true while any activity on the token has an open canceling phase —
     * the last-closer check for a deferred boundary continuation.
     */
    boolean hasOpenCancelingListenerPhaseOnToken(UUID tokenId);

    /**
     * WO-C8-28: true while any activity in the instance has an open canceling phase —
     * the last-closer check for a deferred process-cancel tail.
     */
    boolean hasOpenCancelingListenerPhaseInInstance(UUID processInstanceId);

    void completeUserTask(UUID serviceTaskId);

    void claimUserTask(UUID taskId, String assignee);

    void unclaimUserTask(UUID taskId);

    void assignUserTask(UUID taskId, String assignee);

    Activity getActivity(UUID activityId);

    /**
     * WO-REL-30 (B-3): activity + its process-instance lock in ONE
     * {@code SELECT ... FOR UPDATE} — drop-in для {@code lockAndReload}.
     */
    Activity getActivityForUpdate(UUID activityId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    /** Merged view of the process-instance root scope and a local {@code scopeId} (local shadows root). */
    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    /** Writes variables into a local scope ({@code scopeId == null} writes the process-instance root). */
    void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables);

    /**
     * WO-PERF-9 (B-8, full-scan): pinpoint read of a few ROOT variables by
     * name — one indexed SELECT instead of the full instance scope.
     * See {@link com.zorrodev.bpm.engine.service.db.VariableDbOperations#getVariablesByNames}.
     */
    List<ProcessVariable> getVariablesByNames(@NonNull UUID processInstanceId,
        java.util.Collection<String> names);

    /**
     * WO-PERF-9 (B-8, full-scan): scoped pinpoint — the merged root+scope
     * view restricted to the named rows ("scoped wins" preserved).
     * See {@link com.zorrodev.bpm.engine.service.db.VariableDbOperations#getScopedVariablesByNames}.
     */
    List<ProcessVariable> getScopedVariablesByNames(@NonNull UUID processInstanceId, UUID scopeId,
        java.util.Collection<String> names);

    /**
     * WO-REL-41 (B-8, п.2): pinpoint read of ONE root variable's text value.
     * See {@link com.zorrodev.bpm.engine.service.db.VariableDbOperations#getVariableTextValue}.
     */
    Optional<String> getVariableTextValue(@NonNull UUID processInstanceId, String name);

    /**
     * WO-REL-41 (B-8, п.1): atomic JSON-list append of one element.
     * See {@link com.zorrodev.bpm.engine.service.db.VariableDbOperations#appendJsonElement}.
     */
    void appendJsonElement(@NonNull UUID processInstanceId, String name, String jsonElement);

    /**
     * WO-DIFF-3 (#4): atomic positional JSON-list write of one element at a
     * fixed index (null-padded when short, created when absent).
     * See {@link com.zorrodev.bpm.engine.service.db.VariableDbOperations#setJsonElementAt}.
     */
    void setJsonElementAt(@NonNull UUID processInstanceId, String name, int index, String jsonElement);

    /** Drops all variables of a local scope (e.g. an activity's IO-mapping inputs after it completes). */
    void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId);

    /**
     * WO-ENG-16 (WB-003): хронология изменений переменных инстанса (старые
     * первые). Минимальный read-путь истории (WO допускает service-уровень).
     */
    List<com.zorrodev.bpm.engine.service.db.VariableHistoryEntry> getVariableHistory(
        @NonNull UUID processInstanceId);

    /** То же для одной переменной. */
    List<com.zorrodev.bpm.engine.service.db.VariableHistoryEntry> getVariableHistory(
        @NonNull UUID processInstanceId, String name);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Token createToken(UUID parentId);

    Token createToken(UUID parentId, UUID scopeActivityId);

    Token getToken(UUID tokenId);

    /**
     * WO-REL-30 (B-4): non-throwing token read — empty when the token is gone
     * (stale reference after cancel/cleanup races). See {@code TokenDbOperations.findToken}.
     */
    java.util.Optional<Token> findToken(UUID tokenId);

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

    Integer getMaxProcessDefinitionVersionByKeyAndVersionTag(String key, String versionTag);

    /** WO-C8-3b: latest version of {@code key} laid down in the given deployment (null if none). */
    Integer getMaxProcessDefinitionVersionByKeyAndDeploymentId(String key, UUID deploymentId);

    /** WO-C8-3b: deployment id of a process definition version (null = laid down singly). */
    UUID getDeploymentIdByProcessDefinitionId(UUID processDefinitionId);

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

    /**
     * WO-REL-31 CR-3: keyset-paged fan-out — returns up to {@link #FAN_OUT_BATCH_SIZE} rows
     * in deterministic id-DESC order. Cursor is the minimum id from the previous page
     * (null = first page).
     */
    List<com.zorrodev.bpm.engine.dto.MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId, UUID cursorId);

    /** Active subscriptions matching the message name and correlation-key value (targeted delivery). */
    List<com.zorrodev.bpm.engine.dto.MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey);

    /** Keyset-paged variant of {@link #findMessageSubscriptionsByKey(String, String)} — batch ≤ {@link #FAN_OUT_BATCH_SIZE}. */
    List<com.zorrodev.bpm.engine.dto.MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey, UUID cursorId);

    boolean consumeMessageSubscription(UUID subscriptionId);

    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName);

    /** Creates a signal subscription for a signal boundary event attached to {@code activityId}. */
    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId);

    /** Creates an instance-scoped signal subscription that triggers a signal-started event sub-process. */
    UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId);

    /** All active (unconsumed) subscriptions for {@code signalName}; a signal throw wakes them all. */
    List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName);

    /** Keyset-paged variant — returns up to {@link #FAN_OUT_BATCH_SIZE} rows in id-DESC order. */
    List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName, UUID cursorId);

    boolean consumeSignalSubscription(UUID subscriptionId);

    // WO-REL-31 CR-3: fan-out batch size — cursor-paged finders return at most this many rows per page.
    int FAN_OUT_BATCH_SIZE = 500;

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
