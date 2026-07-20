package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.MessageStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DBServiceImpl implements DBService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final UserTaskRepository userTaskRepository;
    private final VariableRepository variableRepository;
    private final TokenRepository tokenRepository;
    private final IncidentRepository incidentRepository;
    private final TimerJobRepository timerJobRepository;
    private final MessageSubscriptionRepository messageSubscriptionRepository;
    private final SignalSubscriptionRepository signalSubscriptionRepository;
    private final SignalStartSubscriptionRepository signalStartSubscriptionRepository;
    private final MessageStartSubscriptionRepository messageStartSubscriptionRepository;
    private final TimerStartJobRepository timerStartJobRepository;
    private final ParallelGatewayRepository parallelGatewayRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final com.zorrodev.bpm.engine.event.DomainEventEmitter domainEventEmitter;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setStartedAt(Instant.now());
        entity.setParentActivityId(parentActivityId);
        processInstanceRepository.save(entity);
        List<ProcessVariableEntity> vs = new LinkedList<>();
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            ProcessVariableEntity v = new ProcessVariableEntity();
            v.setId(UUID.randomUUID());
            v.setProcessInstanceId(id);
            v.setName(variable.getName());
            v.setType(variable.getType());
            v.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            vs.add(v);
        }
        variableRepository.saveAll(vs);
        domainEventEmitter.emitProcessInstanceStarted(id, processDefinitionId);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(element.getType());
        entity.setBpmnElementId(element.getId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnFlowModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(BpmnElementType.SEQUENCE_FLOW);
        entity.setBpmnElementId(element.getFlowId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public void completeActivity(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.COMPLETED, Instant.now());
        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        domainEventEmitter.emitActivityCompleted(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId());
    }

    @Override
    public void errorActivity(UUID activityId) {
        // ERROR is a parked state, not a completion: leave completedAt unset
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.ERROR, null);
    }

    @Override
    public void cancelActivity(UUID activityId) {
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.CANCELLED, Instant.now());
    }

    @Override
    public void cancelActiveActivities(UUID processInstanceId) {
        List<ActivityEntity> active = activityRepository.findByProcessInstanceIdAndStatusIn(
            processInstanceId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        for (ActivityEntity activity : active) {
            activityRepository.setStatusAndCompletedAt(activity.getId(), ActivityStatus.CANCELLED, Instant.now());
        }
    }

    @Override
    public void cancelActiveActivitiesForToken(UUID tokenId) {
        List<ActivityEntity> active = activityRepository.findByTokenAndStatusIn(
            tokenId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        for (ActivityEntity activity : active) {
            activityRepository.setStatusAndCompletedAt(activity.getId(), ActivityStatus.CANCELLED, Instant.now());
        }
    }

    @Override
    public List<Activity> getActiveActivities(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdAndStatusIn(
                processInstanceId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public boolean hasActiveActivityOnTokenAndElement(UUID tokenId, String bpmnElementId) {
        return !activityRepository.findByTokenAndBpmnElementIdAndStatusIn(
                tokenId, bpmnElementId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS))
            .isEmpty();
    }

    @Override
    public List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds) {
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds).stream()
            .map(entity -> {
                Incident i = new Incident();
                i.setId(entity.getId());
                i.setActivityId(entity.getActivityId());
                i.setMessage(entity.getMessage());
                i.setCreatedAt(entity.getCreatedAt());
                i.setCompletedAt(entity.getCompletedAt());
                return i;
            })
            .toList();
    }

    @Override
    public void completeIncidentsByActivityIds(List<UUID> activityIds) {
        List<IncidentEntity> open = incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds);
        Instant now = Instant.now();
        for (IncidentEntity entity : open) {
            entity.setCompletedAt(now);
        }
        incidentRepository.saveAll(open);
    }

    @Override
    public List<Activity> getCompletedActivities(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdAndStatusIn(
                processInstanceId, List.of(ActivityStatus.COMPLETED)).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity entity = processInstanceRepository.findById(processInstanceId).orElseThrow();
        return processInstanceMapper.toDTO(entity);
    }

    @Override
    public void lockProcessInstance(UUID processInstanceId) {
        processInstanceRepository.findByIdForUpdate(processInstanceId).orElseThrow();
    }

    @Override
    public void createServiceTask(UUID activityId) {
        createServiceTask(activityId, 3);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setRetriesRemaining(retriesRemaining);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        serviceTaskRepository.save(entity);
        domainEventEmitter.emitServiceTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId);
    }

    @Override
    public int decrementServiceTaskRetries(UUID serviceTaskId) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        int remaining = (entity.getRetriesRemaining() == null ? 1 : entity.getRetriesRemaining()) - 1;
        entity.setRetriesRemaining(remaining);
        serviceTaskRepository.save(entity);
        return remaining;
    }

    @Override
    public void setServiceTaskRetries(UUID serviceTaskId, int retries) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setRetriesRemaining(retries);
        serviceTaskRepository.save(entity);
    }

    @Override
    public void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setAssignee(assignee);
        entity.setCandidateGroups(candidateGroups);
        entity.setFormKey(formKey);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
        domainEventEmitter.emitUserTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId, assignee, candidateGroups);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCompletedAt(serviceTaskId, Instant.now());
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        UserTaskEntity ut = userTaskRepository.findById(userTaskId).orElseThrow();
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
        domainEventEmitter.emitUserTaskCompleted(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), userTaskId, ut.getAssignee());
    }

    @Override
    public void claimUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        // Atomic claim: only one concurrent claimant wins (WHERE assignee IS NULL).
        int updated = userTaskRepository.claimAssignee(taskId, assignee);
        if (updated == 0) {
            throw new IllegalStateException("User task is already assigned");
        }
        domainEventEmitter.emitUserTaskAssigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId, assignee);
    }

    @Override
    public void unclaimUserTask(UUID taskId) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, null);
        domainEventEmitter.emitUserTaskUnassigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId);
    }

    @Override
    public void assignUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, assignee);
        domainEventEmitter.emitUserTaskAssigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId, assignee);
    }

    @Override
    public Activity getActivity(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        // the process-instance root scope (scope_id IS NULL) — the instance-level view used everywhere
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .map(this::toProcessVariable)
            .toList();
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        // merged view: root scope plus the local scope, with the local scope shadowing the root by name
        Map<String, ProcessVariable> merged = new LinkedHashMap<>();
        for (ProcessVariableEntity e : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        for (ProcessVariableEntity e : variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, scopeId)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        return new ArrayList<>(merged.values());
    }

    private ProcessVariable toProcessVariable(ProcessVariableEntity variable) {
        ProcessVariable result = new ProcessVariable();
        result.setName(variable.getName());
        result.setType(variable.getType());
        result.setValue(variable.getTextValue());
        return result;
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        setVariables(processInstanceId, null, variables);
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables) {
        List<ProcessVariableEntity> entities = new ArrayList<>();
        for (ProcessVariable variable : variables) {
            ProcessVariableEntity entity = (scopeId == null
                ? variableRepository.findByNameAndProcessInstanceIdAndScopeIdIsNull(variable.getName(), processInstanceId)
                : variableRepository.findByNameAndProcessInstanceIdAndScopeId(variable.getName(), processInstanceId, scopeId))
                .orElseGet(() -> {
                    ProcessVariableEntity e = new ProcessVariableEntity();
                    e.setId(UUID.randomUUID());
                    e.setProcessInstanceId(processInstanceId);
                    e.setName(variable.getName());
                    e.setScopeId(scopeId);
                    return e;
                });
            entity.setType(variable.getType());
            entity.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            entities.add(entity);
        }
        variableRepository.saveAll(entities);
    }

    @Override
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableRepository.deleteByProcessInstanceIdAndScopeId(processInstanceId, scopeId);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityRepository.findByTokenAndBpmnElementId(token, bpmnElementId).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public ProcessDefinition getProcessDefinition(String key, Integer version) {
        ProcessDefinitionEntity entity = processDefinitionRepository.findByKeyAndVersion(key, version).orElseThrow();
        ProcessDefinition result = new ProcessDefinition();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setKey(entity.getKey());
        result.setSha256(entity.getSha256());
        result.setCreatedAt(entity.getCreatedAt());
        result.setStartFormKey(entity.getStartFormKey());
        result.setVersion(entity.getVersion());
        return result;
    }

    @Override
    public Token createToken(UUID parentId) {
        return createToken(parentId, null);
    }

    @Override
    public Token createToken(UUID parentId, UUID scopeActivityId) {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(UUID.randomUUID());
        tokenEntity.setParentId(parentId);
        tokenEntity.setScopeActivityId(scopeActivityId);
        tokenRepository.save(tokenEntity);

        return toToken(tokenEntity);
    }

    @Override
    public Token getToken(UUID tokenId) {
        return tokenRepository.findById(tokenId)
            .map(this::toToken)
            .orElseThrow();
    }

    private Token toToken(TokenEntity entity) {
        Token token = new Token();
        token.setId(entity.getId());
        token.setParentId(entity.getParentId());
        token.setScopeActivityId(entity.getScopeActivityId());
        return token;
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKey(String key) {
        return processDefinitionRepository.findMaxByKey(key).orElse(0);
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        domainEventEmitter.emitProcessInstanceCompleted(processInstanceId, pi.getProcessDefinitionId());
    }

    @Override
    public void cancelProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        processInstanceRepository.setCancelled(processInstanceId, true);
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        domainEventEmitter.emitProcessInstanceCancelled(processInstanceId, pi.getProcessDefinitionId());
    }

    @Override
    public void deleteTimerJobsByProcessInstanceId(UUID processInstanceId) {
        timerJobRepository.deleteByProcessInstanceId(processInstanceId);
    }

    @Override
    public void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId) {
        messageSubscriptionRepository.deleteByProcessInstanceId(processInstanceId);
    }

    @Override
    public UUID createIncident(UUID activityId, String message) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        UUID id = UUID.randomUUID();

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setCreatedAt(Instant.now());
        entity.setMessage(message);
        incidentRepository.save(entity);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activityEntity.getProcessInstanceId()).orElseThrow();
        domainEventEmitter.emitIncidentRaised(activityEntity.getProcessInstanceId(), pi.getProcessDefinitionId(), activityEntity.getBpmnElementId(), id, message);

        return id;
    }

    @Override
    public Incident getIncident(UUID incidentId) {
        IncidentEntity entity = incidentRepository.findById(incidentId).orElseThrow();
        Incident incident = new Incident();
        incident.setId(entity.getId());
        incident.setActivityId(entity.getActivityId());
        incident.setMessage(entity.getMessage());
        incident.setCreatedAt(entity.getCreatedAt());
        incident.setCompletedAt(entity.getCompletedAt());
        return incident;
    }

    @Override
    public void completeIncident(UUID incidentId) {
        IncidentEntity entity = incidentRepository.findById(incidentId).orElseThrow();
        entity.setCompletedAt(Instant.now());
        incidentRepository.save(entity);

        ActivityEntity activity = activityRepository.findById(entity.getActivityId()).orElseThrow();
        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        domainEventEmitter.emitIncidentResolved(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), incidentId);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt) {
        return createTimerJob(activityId, dueAt, null);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId) {
        return createTimerJob(activityId, dueAt, boundaryElementId, null);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId, Integer remainingCount) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        entity.setRemainingCount(remainingCount);
        timerJobRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessTimerJob(UUID processInstanceId, Instant dueAt, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(null);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setProcessInstanceId(processInstanceId);
        entity.setEventSubprocessId(eventSubprocessId);
        timerJobRepository.save(entity);
        return id;
    }

    @Override
    public List<TimerJob> findDueTimerJobs(Instant now) {
        return timerJobRepository.findByFiredFalseAndDueAtLessThanEqual(now).stream()
            .map(e -> {
                TimerJob job = new TimerJob();
                job.setId(e.getId());
                job.setActivityId(e.getActivityId());
                job.setDueAt(e.getDueAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    @Override
    public List<TimerJob> findDueTimerJobsLocked(Instant now) {
        return timerJobRepository.findDueLocked(now).stream()
            .map(e -> {
                TimerJob job = new TimerJob();
                job.setId(e.getId());
                job.setActivityId(e.getActivityId());
                job.setDueAt(e.getDueAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    @Override
    public boolean claimTimerJob(UUID timerJobId) {
        return timerJobRepository.claimTimerJob(timerJobId) > 0;
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName) {
        return createMessageSubscription(processInstanceId, activityId, messageName, null, null);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId) {
        return createMessageSubscription(processInstanceId, activityId, messageName, boundaryElementId, null);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setMessageName(messageName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        entity.setCorrelationKey(correlationKey);
        messageSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(null);
        entity.setMessageName(messageName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setEventSubprocessId(eventSubprocessId);
        messageSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId) {
        List<MessageSubscriptionEntity> entities = processInstanceId != null
            ? messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndProcessInstanceId(messageName, processInstanceId)
            : messageSubscriptionRepository.findByConsumedFalseAndMessageName(messageName);
        return toMessageSubscriptions(entities);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey) {
        return toMessageSubscriptions(
            messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndCorrelationKey(messageName, correlationKey));
    }

    private List<MessageSubscription> toMessageSubscriptions(List<MessageSubscriptionEntity> entities) {
        return entities.stream()
            .map(e -> {
                MessageSubscription sub = new MessageSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setMessageName(e.getMessageName());
                sub.setBoundaryElementId(e.getBoundaryElementId());
                sub.setEventSubprocessId(e.getEventSubprocessId());
                return sub;
            })
            .toList();
    }

    @Override
    public void consumeMessageSubscription(UUID subscriptionId) {
        MessageSubscriptionEntity entity = messageSubscriptionRepository.findById(subscriptionId).orElseThrow();
        entity.setConsumed(true);
        messageSubscriptionRepository.save(entity);
    }

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName) {
        return createSignalSubscription(processInstanceId, activityId, signalName, null);
    }

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId) {
        UUID id = UUID.randomUUID();
        SignalSubscriptionEntity entity = new SignalSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setSignalName(signalName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        signalSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        SignalSubscriptionEntity entity = new SignalSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(null);
        entity.setSignalName(signalName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setEventSubprocessId(eventSubprocessId);
        signalSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.SignalSubscription> findSignalSubscriptions(String signalName) {
        return signalSubscriptionRepository.findByConsumedFalseAndSignalName(signalName).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.SignalSubscription sub = new com.zorrodev.bpm.engine.dto.SignalSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setSignalName(e.getSignalName());
                sub.setBoundaryElementId(e.getBoundaryElementId());
                sub.setEventSubprocessId(e.getEventSubprocessId());
                return sub;
            })
            .toList();
    }

    @Override
    public void consumeSignalSubscription(UUID subscriptionId) {
        SignalSubscriptionEntity entity = signalSubscriptionRepository.findById(subscriptionId).orElseThrow();
        entity.setConsumed(true);
        signalSubscriptionRepository.save(entity);
    }

    @Override
    public void createSignalStartSubscription(String processKey, UUID processDefinitionId, String elementId, String signalName) {
        SignalStartSubscriptionEntity entity = new SignalStartSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setSignalName(signalName);
        entity.setCreatedAt(Instant.now());
        signalStartSubscriptionRepository.save(entity);
    }

    @Override
    public void deleteSignalStartSubscriptionsByKey(String processKey) {
        signalStartSubscriptionRepository.deleteByProcessKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> findSignalStartSubscriptions(String signalName) {
        return signalStartSubscriptionRepository.findBySignalName(signalName).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.SignalStartSubscription sub = new com.zorrodev.bpm.engine.dto.SignalStartSubscription();
                sub.setId(e.getId());
                sub.setProcessKey(e.getProcessKey());
                sub.setProcessDefinitionId(e.getProcessDefinitionId());
                sub.setElementId(e.getElementId());
                sub.setSignalName(e.getSignalName());
                return sub;
            })
            .toList();
    }

    @Override
    public void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName) {
        MessageStartSubscriptionEntity entity = new MessageStartSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setMessageName(messageName);
        entity.setCreatedAt(Instant.now());
        messageStartSubscriptionRepository.save(entity);
    }

    @Override
    public void deleteMessageStartSubscriptionsByKey(String processKey) {
        messageStartSubscriptionRepository.deleteByProcessKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> findMessageStartSubscriptions(String messageName) {
        return messageStartSubscriptionRepository.findByMessageName(messageName).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.MessageStartSubscription sub = new com.zorrodev.bpm.engine.dto.MessageStartSubscription();
                sub.setId(e.getId());
                sub.setProcessKey(e.getProcessKey());
                sub.setProcessDefinitionId(e.getProcessDefinitionId());
                sub.setElementId(e.getElementId());
                sub.setMessageName(e.getMessageName());
                return sub;
            })
            .toList();
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt) {
        TimerStartJobEntity entity = new TimerStartJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        timerStartJobRepository.save(entity);
    }

    @Override
    public void deleteTimerStartJobsByKey(String processKey) {
        timerStartJobRepository.deleteByProcessKey(processKey);
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobs(Instant now) {
        return timerStartJobRepository.findByFiredFalseAndDueAtLessThanEqual(now).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.TimerStartJob job = new com.zorrodev.bpm.engine.dto.TimerStartJob();
                job.setId(e.getId());
                job.setProcessKey(e.getProcessKey());
                job.setProcessDefinitionId(e.getProcessDefinitionId());
                job.setElementId(e.getElementId());
                job.setDueAt(e.getDueAt());
                return job;
            })
            .toList();
    }

    @Override
    public List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobsLocked(Instant now) {
        return timerStartJobRepository.findDueLocked(now).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.TimerStartJob job = new com.zorrodev.bpm.engine.dto.TimerStartJob();
                job.setId(e.getId());
                job.setProcessKey(e.getProcessKey());
                job.setProcessDefinitionId(e.getProcessDefinitionId());
                job.setElementId(e.getElementId());
                job.setDueAt(e.getDueAt());
                return job;
            })
            .toList();
    }

    @Override
    public boolean claimTimerStartJob(UUID timerStartJobId) {
        return timerStartJobRepository.claimTimerStartJob(timerStartJobId) > 0;
    }

    @Override
    public void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId) {
        if (parallelGatewayRepository.existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(
                processInstanceId, gatewayElementId, enteredFlowId)) {
            return;
        }
        ParallelGatewayEntity entity = new ParallelGatewayEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessInstanceId(processInstanceId);
        entity.setGatewayElementId(gatewayElementId);
        entity.setEnteredFlowId(enteredFlowId);
        entity.setCreatedAt(Instant.now());
        parallelGatewayRepository.save(entity);
    }

    @Override
    public Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId) {
        return new HashSet<>(parallelGatewayRepository.findEnteredFlows(processInstanceId, gatewayElementId));
    }

    @Override
    public void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId) {
        parallelGatewayRepository.deleteByProcessInstanceIdAndGatewayElementId(processInstanceId, gatewayElementId);
    }

    @Override
    public void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount) {
        ParallelGatewayEntity entity = new ParallelGatewayEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessInstanceId(processInstanceId);
        entity.setGatewayElementId(gatewayElementId);
        // marker row (holds the expected count, not an arrival); entered_flow_id is NOT NULL in the
        // schema, so reuse the gateway id and distinguish marker rows by expected_count being set.
        entity.setEnteredFlowId(gatewayElementId);
        entity.setExpectedCount(expectedCount);
        entity.setCreatedAt(Instant.now());
        parallelGatewayRepository.save(entity);
    }

    @Override
    public Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId) {
        return parallelGatewayRepository.findExpectedCounts(processInstanceId, gatewayElementId)
            .stream().findFirst().orElse(null);
    }

    private Activity getActivity(ActivityEntity activityEntity) {
        Activity activity = new Activity();
        activity.setId(activityEntity.getId());
        activity.setProcessInstanceId(activityEntity.getProcessInstanceId());
        activity.setBpmnElementId(activityEntity.getBpmnElementId());
        activity.setCreatedAt(activityEntity.getCreatedAt());
        activity.setCompletedAt(activityEntity.getCompletedAt());
        activity.setStatus(activityEntity.getStatus());
        activity.setType(activityEntity.getType());
        activity.setToken(activityEntity.getToken());
        return activity;
    }
}
