package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.service.query.ActivityQueryOperations;
import com.zorrodev.bpm.engine.service.query.IncidentQueryOperations;
import com.zorrodev.bpm.engine.service.query.MessageSubscriptionQueryOperations;
import com.zorrodev.bpm.engine.service.query.ProcessInstanceQueryOperations;
import com.zorrodev.bpm.engine.service.query.ServiceTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.TimerJobQueryOperations;
import com.zorrodev.bpm.engine.service.query.UserTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.VariableQueryOperations;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.engine.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    private final ActivityQueryOperations activityQueryOperations;
    private final ServiceTaskQueryOperations serviceTaskQueryOperations;
    private final UserTaskQueryOperations userTaskQueryOperations;
    private final ProcessInstanceQueryOperations processInstanceQueryOperations;
    private final IncidentQueryOperations incidentQueryOperations;
    private final TimerJobQueryOperations timerJobQueryOperations;
    private final MessageSubscriptionQueryOperations messageSubscriptionQueryOperations;
    private final VariableQueryOperations variableQueryOperations;

    @Override
    public PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query, Collection<UUID> allowedPdIds) {
        return timerJobQueryOperations.findTimerJobs(query, allowedPdIds);
    }

    @Override
    public PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query, Collection<UUID> allowedPdIds) {
        return messageSubscriptionQueryOperations.findMessageSubscriptions(query, allowedPdIds);
    }

    @Override
    public List<ActivityInstance> getActivities(UUID processInstanceId) {
        return activityQueryOperations.getActivities(processInstanceId);
    }

    @Override
    public PagedDataDTO<ActivityInstance> getActivitiesPaged(UUID processInstanceId, Integer pageIndex, Integer pageSize) {
        return activityQueryOperations.getActivitiesPaged(processInstanceId, pageIndex, pageSize);
    }

    @Override
    public PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query, Collection<UUID> allowedPdIds) {
        return serviceTaskQueryOperations.findServiceTasks(query, allowedPdIds);
    }

    @Override
    public ServiceTask getServiceTask(UUID id) {
        return serviceTaskQueryOperations.getServiceTask(id);
    }

    @Override
    public PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds) {
        return userTaskQueryOperations.findUserTasks(query, allowedPdIds);
    }

    @Override
    public UserTask getUserTask(UUID id) {
        return userTaskQueryOperations.getUserTask(id);
    }

    @Override
    public ProcessInstance getProcessInstance(UUID id) {
        return processInstanceQueryOperations.getProcessInstance(id);
    }

    @Override
    public PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds) {
        return processInstanceQueryOperations.findProcessInstances(query, allowedPdIds);
    }

    @Override
    public Incident getIncident(UUID id) {
        return incidentQueryOperations.getIncident(id);
    }

    @Override
    public UUID resolveIncidentProcessDefinitionId(UUID incidentId) {
        return incidentQueryOperations.resolveIncidentProcessDefinitionId(incidentId);
    }

    @Override
    public PagedDataDTO<Incident> findIncidents(IncidentQuery query, Collection<UUID> allowedPdIds) {
        return incidentQueryOperations.findIncidents(query, allowedPdIds);
    }

    @Override
    public PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds) {
        return variableQueryOperations.findVariables(query, allowedPdIds);
    }

}
