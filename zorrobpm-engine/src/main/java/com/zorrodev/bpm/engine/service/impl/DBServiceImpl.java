package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.service.db.MessageSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.SignalSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.TimerDbOperations;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessDefinitionDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations;
import com.zorrodev.bpm.engine.service.db.ServiceTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.TokenDbOperations;
import com.zorrodev.bpm.engine.service.db.UserTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.VariableDbOperations;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DBServiceImpl implements DBService {

    private final ProcessDefinitionDbOperations processDefinitionDbOperations;
    private final ParallelGatewayDbOperations parallelGatewayDbOperations;
    private final ProcessInstanceDbOperations processInstanceDbOperations;
    private final ServiceTaskDbOperations serviceTaskDbOperations;
    private final UserTaskDbOperations userTaskDbOperations;
    private final IncidentDbOperations incidentDbOperations;
    private final MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    private final SignalSubscriptionDbOperations signalSubscriptionDbOperations;
    private final TimerDbOperations timerDbOperations;
    private final ActivityDbOperations activityDbOperations;
    private final VariableDbOperations variableDbOperations;
    private final TokenDbOperations tokenDbOperations;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        return processInstanceDbOperations.createProcessInstance(parentActivityId, processDefinitionId, variables);
    }

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
            List<ProcessVariable> variables, String claimedInitiator) {
        return processInstanceDbOperations.createProcessInstance(
            parentActivityId, processDefinitionId, variables, claimedInitiator);
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element) {
        return activityDbOperations.createActivity(processInstanceId, token, element);
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnFlowModel element) {
        return activityDbOperations.createActivity(processInstanceId, token, element);
    }

    @Override
    public void completeActivity(UUID activityId) {
        activityDbOperations.completeActivity(activityId);
    }

    @Override
    public void errorActivity(UUID activityId) {
        activityDbOperations.errorActivity(activityId);
    }

    @Override
    public void cancelActivity(UUID activityId) {
        activityDbOperations.cancelActivity(activityId);
    }

    @Override
    public void cancelActiveActivities(UUID processInstanceId) {
        activityDbOperations.cancelActiveActivities(processInstanceId);
    }

    @Override
    public void cancelActiveActivitiesForToken(UUID tokenId) {
        activityDbOperations.cancelActiveActivitiesForToken(tokenId);
    }

    @Override
    public List<Activity> getActiveActivities(UUID processInstanceId) {
        return activityDbOperations.getActiveActivities(processInstanceId);
    }

    @Override
    public boolean hasActiveActivityOnTokenAndElement(UUID tokenId, String bpmnElementId) {
        return activityDbOperations.hasActiveActivityOnTokenAndElement(tokenId, bpmnElementId);
    }

    @Override
    public List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds) {
        return incidentDbOperations.findOpenIncidentsByActivityIds(activityIds);
    }

    @Override
    public void completeIncidentsByActivityIds(List<UUID> activityIds) {
        incidentDbOperations.completeIncidentsByActivityIds(activityIds);
    }

    @Override
    public List<Activity> getCompletedActivities(UUID processInstanceId) {
        return activityDbOperations.getCompletedActivities(processInstanceId);
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        return processInstanceDbOperations.getProcessInstance(processInstanceId);
    }

    @Override
    public void lockProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.lockProcessInstance(processInstanceId);
    }

    @Override
    public void createServiceTask(UUID activityId) {
        serviceTaskDbOperations.createServiceTask(activityId);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining, String job) {
        serviceTaskDbOperations.createServiceTask(activityId, retriesRemaining, job);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining, String job, Integer pendingListenerIndex) {
        serviceTaskDbOperations.createServiceTask(activityId, retriesRemaining, job, pendingListenerIndex);
    }

    @Override
    public void setPendingListenerIndex(UUID serviceTaskId, Integer pendingListenerIndex) {
        serviceTaskDbOperations.setPendingListenerIndex(serviceTaskId, pendingListenerIndex);
    }

    @Override
    public Integer getServiceTaskPendingListenerIndex(UUID serviceTaskId) {
        return serviceTaskDbOperations.getPendingListenerIndex(serviceTaskId);
    }

    @Override
    public void setPendingEndListenerIndex(UUID serviceTaskId, Integer pendingEndListenerIndex) {
        serviceTaskDbOperations.setPendingEndListenerIndex(serviceTaskId, pendingEndListenerIndex);
    }

    @Override
    public Integer getServiceTaskPendingEndListenerIndex(UUID serviceTaskId) {
        return serviceTaskDbOperations.getPendingEndListenerIndex(serviceTaskId);
    }

    @Override
    public int decrementServiceTaskRetries(UUID serviceTaskId) {
        return serviceTaskDbOperations.decrementServiceTaskRetries(serviceTaskId);
    }

    @Override
    public void setServiceTaskRetries(UUID serviceTaskId, int retries) {
        serviceTaskDbOperations.setServiceTaskRetries(serviceTaskId, retries);
    }

    @Override
    public void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String bindingType, String dueDate, String followUpDate, Integer priority) {
        userTaskDbOperations.createUserTask(activityId, assignee, candidateGroups, formKey, formId, bindingType, dueDate, followUpDate, priority);
    }

    @Override
    public void setPendingCreatingListenerIndex(UUID activityId, Integer index) {
        activityDbOperations.setPendingCreatingListenerIndex(activityId, index);
    }

    @Override
    public Integer getPendingCreatingListenerIndex(UUID activityId) {
        return activityDbOperations.getPendingCreatingListenerIndex(activityId);
    }

    @Override
    public void setCreatingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        activityDbOperations.setCreatingListenerRetriesRemaining(activityId, remaining);
    }

    @Override
    public Integer getCreatingListenerRetriesRemaining(UUID activityId) {
        return activityDbOperations.getCreatingListenerRetriesRemaining(activityId);
    }

    @Override
    public void setPendingCompletingListenerIndex(UUID activityId, Integer index) {
        activityDbOperations.setPendingCompletingListenerIndex(activityId, index);
    }

    @Override
    public Integer getPendingCompletingListenerIndex(UUID activityId) {
        return activityDbOperations.getPendingCompletingListenerIndex(activityId);
    }

    @Override
    public void setCompletingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        activityDbOperations.setCompletingListenerRetriesRemaining(activityId, remaining);
    }

    @Override
    public Integer getCompletingListenerRetriesRemaining(UUID activityId) {
        return activityDbOperations.getCompletingListenerRetriesRemaining(activityId);
    }

    // WO-C8-28: assigning/updating/canceling phase delegation — mechanical mirror
    // of the creating/completing pairs above.

    @Override
    public void setPendingAssigningListenerIndex(UUID activityId, Integer index) {
        activityDbOperations.setPendingAssigningListenerIndex(activityId, index);
    }

    @Override
    public Integer getPendingAssigningListenerIndex(UUID activityId) {
        return activityDbOperations.getPendingAssigningListenerIndex(activityId);
    }

    @Override
    public void setAssigningListenerRetriesRemaining(UUID activityId, Integer remaining) {
        activityDbOperations.setAssigningListenerRetriesRemaining(activityId, remaining);
    }

    @Override
    public Integer getAssigningListenerRetriesRemaining(UUID activityId) {
        return activityDbOperations.getAssigningListenerRetriesRemaining(activityId);
    }

    @Override
    public void setPendingAssignee(UUID activityId, String assignee) {
        activityDbOperations.setPendingAssignee(activityId, assignee);
    }

    @Override
    public String getPendingAssignee(UUID activityId) {
        return activityDbOperations.getPendingAssignee(activityId);
    }

    @Override
    public void setPendingUpdatingListenerIndex(UUID activityId, Integer index) {
        activityDbOperations.setPendingUpdatingListenerIndex(activityId, index);
    }

    @Override
    public Integer getPendingUpdatingListenerIndex(UUID activityId) {
        return activityDbOperations.getPendingUpdatingListenerIndex(activityId);
    }

    @Override
    public void setUpdatingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        activityDbOperations.setUpdatingListenerRetriesRemaining(activityId, remaining);
    }

    @Override
    public Integer getUpdatingListenerRetriesRemaining(UUID activityId) {
        return activityDbOperations.getUpdatingListenerRetriesRemaining(activityId);
    }

    @Override
    public void setPendingCancelingListenerIndex(UUID activityId, Integer index) {
        activityDbOperations.setPendingCancelingListenerIndex(activityId, index);
    }

    @Override
    public Integer getPendingCancelingListenerIndex(UUID activityId) {
        return activityDbOperations.getPendingCancelingListenerIndex(activityId);
    }

    @Override
    public void setCancelingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        activityDbOperations.setCancelingListenerRetriesRemaining(activityId, remaining);
    }

    @Override
    public Integer getCancelingListenerRetriesRemaining(UUID activityId) {
        return activityDbOperations.getCancelingListenerRetriesRemaining(activityId);
    }

    @Override
    public void setPendingCancelBoundaryElementId(UUID activityId, String boundaryElementId) {
        activityDbOperations.setPendingCancelBoundaryElementId(activityId, boundaryElementId);
    }

    @Override
    public String getPendingCancelBoundaryElementId(UUID activityId) {
        return activityDbOperations.getPendingCancelBoundaryElementId(activityId);
    }

    @Override
    public boolean hasOpenCancelingListenerPhaseOnToken(UUID tokenId) {
        return activityDbOperations.hasOpenCancelingListenerPhaseOnToken(tokenId);
    }

    @Override
    public boolean hasOpenCancelingListenerPhaseInInstance(UUID processInstanceId) {
        return activityDbOperations.hasOpenCancelingListenerPhaseInInstance(processInstanceId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskDbOperations.completeServiceTask(serviceTaskId);
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        userTaskDbOperations.completeUserTask(userTaskId);
    }

    @Override
    public void claimUserTask(UUID taskId, String assignee) {
        userTaskDbOperations.claimUserTask(taskId, assignee);
    }

    @Override
    public void unclaimUserTask(UUID taskId) {
        userTaskDbOperations.unclaimUserTask(taskId);
    }

    @Override
    public void assignUserTask(UUID taskId, String assignee) {
        userTaskDbOperations.assignUserTask(taskId, assignee);
    }

    @Override
    public Activity getActivity(UUID activityId) {
        return activityDbOperations.getActivity(activityId);
    }

    /**
     * WO-REL-30 (B-3): joins the caller's transaction (the domain boundary on
     * {@code ActivityServiceImpl}/{@code CompletionService} is the normal path);
     * without one the {@code FOR UPDATE} has nothing to hold the lock in.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public Activity getActivityForUpdate(UUID activityId) {
        return activityDbOperations.getActivityForUpdate(activityId);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        return variableDbOperations.getVariables(processInstanceId);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        return variableDbOperations.getVariables(processInstanceId, scopeId);
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        variableDbOperations.setVariables(processInstanceId, variables);
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables) {
        variableDbOperations.setVariables(processInstanceId, scopeId, variables);
    }

    @Override
    public List<ProcessVariable> getVariablesByNames(@NonNull UUID processInstanceId,
            java.util.Collection<String> names) {
        return variableDbOperations.getVariablesByNames(processInstanceId, names);
    }

    @Override
    public List<ProcessVariable> getScopedVariablesByNames(@NonNull UUID processInstanceId, UUID scopeId,
            java.util.Collection<String> names) {
        return variableDbOperations.getScopedVariablesByNames(processInstanceId, scopeId, names);
    }

    @Override
    public Optional<String> getVariableTextValue(@NonNull UUID processInstanceId, String name) {
        return variableDbOperations.getVariableTextValue(processInstanceId, name);
    }

    @Override
    public void appendJsonElement(@NonNull UUID processInstanceId, String name, String jsonElement) {
        variableDbOperations.appendJsonElement(processInstanceId, name, jsonElement);
    }

    @Override
    public void setJsonElementAt(@NonNull UUID processInstanceId, String name, int index, String jsonElement) {
        variableDbOperations.setJsonElementAt(processInstanceId, name, index, jsonElement);
    }

    @Override
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableDbOperations.deleteVariables(processInstanceId, scopeId);
    }

    @Override
    public List<com.zorrodev.bpm.engine.service.db.VariableHistoryEntry> getVariableHistory(
            @NonNull UUID processInstanceId) {
        return variableDbOperations.getVariableHistory(processInstanceId);
    }

    @Override
    public List<com.zorrodev.bpm.engine.service.db.VariableHistoryEntry> getVariableHistory(
            @NonNull UUID processInstanceId, String name) {
        return variableDbOperations.getVariableHistory(processInstanceId, name);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityDbOperations.getActivitiesByTokenAndBpmnElementId(token, bpmnElementId);
    }

    @Override
    public ProcessDefinition getProcessDefinition(String key, Integer version) {
        return processDefinitionDbOperations.getProcessDefinition(key, version);
    }

    @Override
    public Token createToken(UUID parentId) {
        return tokenDbOperations.createToken(parentId);
    }

    @Override
    public Token createToken(UUID parentId, UUID scopeActivityId) {
        return tokenDbOperations.createToken(parentId, scopeActivityId);
    }

    @Override
    public Token getToken(UUID tokenId) {
        return tokenDbOperations.getToken(tokenId);
    }

    @Override
    public java.util.Optional<Token> findToken(UUID tokenId) {
        return tokenDbOperations.findToken(tokenId);
    }

    @Override
    public void deleteToken(UUID tokenId) {
        tokenDbOperations.deleteToken(tokenId);
    }

    @Override
    public void setPendingBranches(UUID tokenId, int count) {
        parallelGatewayDbOperations.setPendingBranches(tokenId, count);
    }

    @Override
    public int decrementPendingBranches(UUID tokenId) {
        return parallelGatewayDbOperations.decrementPendingBranches(tokenId);
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKey(String key) {
        return processDefinitionDbOperations.getMaxProcessDefinitionVersionByKey(key);
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKeyAndVersionTag(String key, String versionTag) {
        return processDefinitionDbOperations.getMaxProcessDefinitionVersionByKeyAndVersionTag(key, versionTag);
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKeyAndDeploymentId(String key, UUID deploymentId) {
        return processDefinitionDbOperations.getMaxProcessDefinitionVersionByKeyAndDeploymentId(key, deploymentId);
    }

    @Override
    public UUID getDeploymentIdByProcessDefinitionId(UUID processDefinitionId) {
        return processDefinitionDbOperations.getDeploymentIdByProcessDefinitionId(processDefinitionId);
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.completeProcessInstance(processInstanceId);
    }

    @Override
    public void cancelProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.cancelProcessInstance(processInstanceId);
    }

    @Override
    public void deleteTimerJobsByProcessInstanceId(UUID processInstanceId) {
        timerDbOperations.deleteTimerJobsByProcessInstanceId(processInstanceId);
    }

    @Override
    public void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId) {
        messageSubscriptionDbOperations.deleteMessageSubscriptionsByProcessInstanceId(processInstanceId);
    }

    @Override
    public UUID createIncident(UUID activityId, String message) {
        return incidentDbOperations.createIncident(activityId, message);
    }

    @Override
    public Incident getIncident(UUID incidentId) {
        return incidentDbOperations.getIncident(incidentId);
    }

    @Override
    public void completeIncident(UUID incidentId) {
        incidentDbOperations.completeIncident(incidentId);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId, Integer remainingCount, String expression, UUID processInstanceId) {
        return timerDbOperations.createTimerJob(activityId, dueAt, boundaryElementId, remainingCount, expression, processInstanceId);
    }

    @Override
    public UUID createEventSubprocessTimerJob(UUID processInstanceId, Instant dueAt, String eventSubprocessId) {
        return timerDbOperations.createEventSubprocessTimerJob(processInstanceId, dueAt, eventSubprocessId);
    }

    @Override
    public List<TimerJob> findDueTimerJobs(Instant now) {
        return timerDbOperations.findDueTimerJobs(now);
    }

    @Override
    @Transactional
    public List<TimerJob> findDueTimerJobsLocked(Instant now, int batchSize) {
        return timerDbOperations.findDueTimerJobsLocked(now, batchSize);
    }

    @Override
    @Transactional
    public boolean claimTimerJob(UUID timerJobId) {
        return timerDbOperations.claimTimerJob(timerJobId);
    }

    @Override
    @Transactional
    public void recordTimerJobError(UUID timerJobId, String errorMessage) {
        timerDbOperations.recordTimerJobError(timerJobId, errorMessage);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName) {
        return messageSubscriptionDbOperations.createMessageSubscription(processInstanceId, activityId, messageName);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId) {
        return messageSubscriptionDbOperations.createMessageSubscription(processInstanceId, activityId, messageName, boundaryElementId);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey) {
        return messageSubscriptionDbOperations.createMessageSubscription(processInstanceId, activityId, messageName, boundaryElementId, correlationKey);
    }

    @Override
    public UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId) {
        return messageSubscriptionDbOperations.createEventSubprocessMessageSubscription(processInstanceId, messageName, eventSubprocessId);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId) {
        return messageSubscriptionDbOperations.findMessageSubscriptions(messageName, processInstanceId);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId, UUID cursorId) {
        return messageSubscriptionDbOperations.findMessageSubscriptions(messageName, processInstanceId, cursorId);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey) {
        return messageSubscriptionDbOperations.findMessageSubscriptionsByKey(messageName, correlationKey);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey, UUID cursorId) {
        return messageSubscriptionDbOperations.findMessageSubscriptionsByKey(messageName, correlationKey, cursorId);
    }

    @Override
    @Transactional
    public boolean consumeMessageSubscription(UUID subscriptionId) {
        return messageSubscriptionDbOperations.consumeMessageSubscription(subscriptionId);
    }

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName) {
        return signalSubscriptionDbOperations.createSignalSubscription(processInstanceId, activityId, signalName);
    }

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId) {
        return signalSubscriptionDbOperations.createSignalSubscription(processInstanceId, activityId, signalName, boundaryElementId);
    }

    @Override
    public UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId) {
        return signalSubscriptionDbOperations.createEventSubprocessSignalSubscription(processInstanceId, signalName, eventSubprocessId);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName) {
        return signalSubscriptionDbOperations.findSignalSubscriptions(signalName);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName, UUID cursorId) {
        return signalSubscriptionDbOperations.findSignalSubscriptions(signalName, cursorId);
    }

    @Override
    @Transactional
    public boolean consumeSignalSubscription(UUID subscriptionId) {
        return signalSubscriptionDbOperations.consumeSignalSubscription(subscriptionId);
    }

    @Override
    public void createSignalStartSubscription(String processKey, UUID processDefinitionId, String elementId, String signalName) {
        signalSubscriptionDbOperations.createSignalStartSubscription(processKey, processDefinitionId, elementId, signalName);
    }

    @Override
    public void deleteSignalStartSubscriptionsByKey(String processKey) {
        signalSubscriptionDbOperations.deleteSignalStartSubscriptionsByKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> findSignalStartSubscriptions(String signalName) {
        return signalSubscriptionDbOperations.findSignalStartSubscriptions(signalName);
    }

    @Override
    public void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName) {
        messageSubscriptionDbOperations.createMessageStartSubscription(processKey, processDefinitionId, elementId, messageName);
    }

    @Override
    public void deleteMessageStartSubscriptionsByKey(String processKey) {
        messageSubscriptionDbOperations.deleteMessageStartSubscriptionsByKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> findMessageStartSubscriptions(String messageName) {
        return messageSubscriptionDbOperations.findMessageStartSubscriptions(messageName);
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt) {
        timerDbOperations.createTimerStartJob(processKey, processDefinitionId, elementId, dueAt);
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt, Integer remainingCount) {
        timerDbOperations.createTimerStartJob(processKey, processDefinitionId, elementId, dueAt, remainingCount);
    }

    @Override
    public void deleteTimerStartJobsByKey(String processKey) {
        timerDbOperations.deleteTimerStartJobsByKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobs(Instant now) {
        return timerDbOperations.findDueTimerStartJobs(now);
    }

    @Override
    @Transactional
    public List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobsLocked(Instant now, int batchSize) {
        return timerDbOperations.findDueTimerStartJobsLocked(now, batchSize);
    }

    @Override
    @Transactional
    public boolean claimTimerStartJob(UUID timerStartJobId) {
        return timerDbOperations.claimTimerStartJob(timerStartJobId);
    }

    @Override
    @Transactional
    public void recordTimerStartJobError(UUID timerStartJobId, String errorMessage) {
        timerDbOperations.recordTimerStartJobError(timerStartJobId, errorMessage);
    }

    @Override
    public void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId) {
        parallelGatewayDbOperations.recordParallelGatewayArrival(processInstanceId, gatewayElementId, enteredFlowId);
    }

    @Override
    public Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId) {
        return parallelGatewayDbOperations.getParallelGatewayArrivedFlows(processInstanceId, gatewayElementId);
    }

    @Override
    public void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId) {
        parallelGatewayDbOperations.clearParallelGatewayArrivals(processInstanceId, gatewayElementId);
    }

    @Override
    public void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount) {
        parallelGatewayDbOperations.recordInclusiveExpected(processInstanceId, gatewayElementId, expectedCount);
    }

    @Override
    public Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId) {
        return parallelGatewayDbOperations.getInclusiveExpected(processInstanceId, gatewayElementId);
    }

}
