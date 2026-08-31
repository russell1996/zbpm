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
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessDefinitionDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations;
import com.zorrodev.bpm.engine.service.db.TokenDbOperations;
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
    private final ProcessInstanceRepository processInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final UserTaskRepository userTaskRepository;
    private final VariableDbOperations variableDbOperations;
    private final TokenDbOperations tokenDbOperations;
    private final IncidentRepository incidentRepository;
    private final TimerJobRepository timerJobRepository;
    private final MessageSubscriptionRepository messageSubscriptionRepository;
    private final SignalSubscriptionRepository signalSubscriptionRepository;
    private final SignalStartSubscriptionRepository signalStartSubscriptionRepository;
    private final MessageStartSubscriptionRepository messageStartSubscriptionRepository;
    private final TimerStartJobRepository timerStartJobRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final com.zorrodev.bpm.engine.event.DomainEventEmitter domainEventEmitter;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        return processInstanceDbOperations.createProcessInstance(parentActivityId, processDefinitionId, variables);
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
        // WO-EVT-9: service tasks carry their stable job id in the event data; other element types keep data empty.
        String job = serviceTaskRepository.findById(activityId).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitActivityCompleted(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), job);
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
        return processInstanceDbOperations.getProcessInstance(processInstanceId);
    }

    @Override
    public void lockProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.lockProcessInstance(processInstanceId);
    }

    @Override
    public void createServiceTask(UUID activityId) {
        createServiceTask(activityId, 3, null);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining, String job) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setRetriesRemaining(retriesRemaining);
        entity.setJob(job);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        serviceTaskRepository.save(entity);
        domainEventEmitter.emitServiceTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId, job);
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
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableDbOperations.deleteVariables(processInstanceId, scopeId);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityRepository.findByTokenAndBpmnElementId(token, bpmnElementId).stream()
            .map(this::getActivity)
            .toList();
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
    public void completeProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.completeProcessInstance(processInstanceId);
    }

    @Override
    public void cancelProcessInstance(UUID processInstanceId) {
        processInstanceDbOperations.cancelProcessInstance(processInstanceId);
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
        // WO-EVT-9: service tasks carry their stable job id in the event data.
        String job = serviceTaskRepository.findById(activityId).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitIncidentRaised(activityEntity.getProcessInstanceId(), pi.getProcessDefinitionId(), activityEntity.getBpmnElementId(), id, message, job);

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
        // WO-EVT-9: service tasks carry their stable job id in the event data.
        String job = serviceTaskRepository.findById(activity.getId()).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitIncidentResolved(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), incidentId, job);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId, Integer remainingCount, String expression, UUID processInstanceId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        entity.setRemainingCount(remainingCount);
        entity.setExpression(expression);
        entity.setProcessInstanceId(processInstanceId);
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
                job.setCreatedAt(e.getCreatedAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                job.setExpression(e.getExpression());
                return job;
            })
            .toList();
    }

    /**
     * WO-REL-13: candidate selection runs in its own SHORT transaction — the SKIP LOCKED row locks
     * are released as soon as the SELECT returns, before any job is fired. Double execution is then
     * prevented by the atomic CAS claim inside each fire's REQUIRES_NEW transaction.
     */
    @Override
    @Transactional
    public List<TimerJob> findDueTimerJobsLocked(Instant now, int batchSize) {
        return timerJobRepository.findDueLocked(now, batchSize).stream()
            .map(e -> {
                TimerJob job = new TimerJob();
                job.setId(e.getId());
                job.setActivityId(e.getActivityId());
                job.setDueAt(e.getDueAt());
                job.setCreatedAt(e.getCreatedAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                job.setExpression(e.getExpression());
                return job;
            })
            .toList();
    }

    @Override
    @Transactional
    public boolean claimTimerJob(UUID timerJobId) {
        // WO-REL-13: NON-BLOCKING claim. The SKIP LOCKED row lock from findDueTimerJobsLocked is
        // released as soon as the selection transaction commits, so two pollers (multinode) can
        // select the SAME due row. A plain UPDATE here would then block on the other poller's
        // uncommitted row lock → cross-poller deadlock. FOR UPDATE SKIP LOCKED makes the claim
        // either win instantly or lose instantly (row already locked → skipped → 0 rows).
        List<UUID> locked = jdbcTemplate.queryForList(
            "SELECT id FROM timer_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED",
            UUID.class, timerJobId);
        if (locked.isEmpty()) {
            return false;
        }
        return timerJobRepository.claimTimerJob(timerJobId) > 0;
    }

    @Override
    @Transactional
    public void recordTimerJobError(UUID timerJobId, String errorMessage) {
        timerJobRepository.recordTimerJobError(timerJobId, errorMessage);
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
    @Transactional
    public boolean consumeMessageSubscription(UUID subscriptionId) {
        // WO-SEC-59 #2: CAS — only one concurrent correlation may consume the subscription.
        // A plain findById+save would let two concurrent callers both observe "not consumed"
        // and both apply the signal/message (double branch on a non-interrupting boundary).
        return messageSubscriptionRepository.markConsumed(subscriptionId) == 1;
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
    @Transactional
    public boolean consumeSignalSubscription(UUID subscriptionId) {
        // WO-SEC-59 #2: CAS — only one concurrent correlation may consume the subscription.
        return signalSubscriptionRepository.markConsumed(subscriptionId) == 1;
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
        createTimerStartJob(processKey, processDefinitionId, elementId, dueAt, null);
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt, Integer remainingCount) {
        TimerStartJobEntity entity = new TimerStartJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setRemainingCount(remainingCount);
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
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    /**
     * WO-REL-13: candidate selection runs in its own SHORT transaction (see findDueTimerJobsLocked).
     */
    @Override
    @Transactional
    public List<com.zorrodev.bpm.engine.dto.TimerStartJob> findDueTimerStartJobsLocked(Instant now, int batchSize) {
        return timerStartJobRepository.findDueLocked(now, batchSize).stream()
            .map(e -> {
                com.zorrodev.bpm.engine.dto.TimerStartJob job = new com.zorrodev.bpm.engine.dto.TimerStartJob();
                job.setId(e.getId());
                job.setProcessKey(e.getProcessKey());
                job.setProcessDefinitionId(e.getProcessDefinitionId());
                job.setElementId(e.getElementId());
                job.setDueAt(e.getDueAt());
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    @Override
    @Transactional
    public boolean claimTimerStartJob(UUID timerStartJobId) {
        // WO-REL-13: NON-BLOCKING claim — see claimTimerJob.
        List<UUID> locked = jdbcTemplate.queryForList(
            "SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED",
            UUID.class, timerStartJobId);
        if (locked.isEmpty()) {
            return false;
        }
        return timerStartJobRepository.claimTimerStartJob(timerStartJobId) > 0;
    }

    @Override
    @Transactional
    public void recordTimerStartJobError(UUID timerStartJobId, String errorMessage) {
        timerStartJobRepository.recordTimerStartJobError(timerStartJobId, errorMessage);
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
