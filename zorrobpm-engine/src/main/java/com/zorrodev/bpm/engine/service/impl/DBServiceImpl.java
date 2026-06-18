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
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
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
import java.util.LinkedList;
import java.util.List;
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
    private final ParallelGatewayRepository parallelGatewayRepository;
    private final ProcessInstanceMapper processInstanceMapper;

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
            v.setTextValue(variable.getValue());
            vs.add(v);
        }
        variableRepository.saveAll(vs);
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
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.COMPLETED, Instant.now());
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
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        serviceTaskRepository.save(entity);
    }

    @Override
    public void createUserTask(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCompletedAt(serviceTaskId, Instant.now());
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
    }

    @Override
    public Activity getActivity(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        List<ProcessVariableEntity> variables = variableRepository.findByProcessInstanceId(processInstanceId);
        return variables.stream()
            .map(variable -> {
                ProcessVariable result = new ProcessVariable();
                result.setName(variable.getName());
                result.setType(variable.getType());
                result.setValue(variable.getTextValue());
                return result;
            })
            .toList();
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        List<ProcessVariableEntity> entities = new ArrayList<>();
        for (ProcessVariable variable : variables) {
            ProcessVariableEntity entity = variableRepository
                .findByNameAndProcessInstanceId(variable.getName(), processInstanceId)
                .orElseGet(() -> {
                    ProcessVariableEntity e = new ProcessVariableEntity();
                    e.setId(UUID.randomUUID());
                    e.setProcessInstanceId(processInstanceId);
                    e.setName(variable.getName());
                    return e;
                });
            entity.setType(variable.getType());
            entity.setTextValue(variable.getValue());
            entities.add(entity);
        }
        variableRepository.saveAll(entities);
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
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
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
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt) {
        return createTimerJob(activityId, dueAt, null);
    }

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
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
                return job;
            })
            .toList();
    }

    @Override
    public void markTimerJobFired(UUID timerJobId) {
        TimerJobEntity entity = timerJobRepository.findById(timerJobId).orElseThrow();
        entity.setFired(true);
        timerJobRepository.save(entity);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setMessageName(messageName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        messageSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId) {
        List<MessageSubscriptionEntity> entities = processInstanceId != null
            ? messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndProcessInstanceId(messageName, processInstanceId)
            : messageSubscriptionRepository.findByConsumedFalseAndMessageName(messageName);
        return entities.stream()
            .map(e -> {
                MessageSubscription sub = new MessageSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setMessageName(e.getMessageName());
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
