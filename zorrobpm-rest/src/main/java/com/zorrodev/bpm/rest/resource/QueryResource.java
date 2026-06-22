package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.QueryContract;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.engine.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class QueryResource implements QueryContract {

    private final QueryService queryService;

    public PagedDataDTO<ProcessVariable> getVariables(@ParameterObject VariableQuery query) {
        return queryService.findVariables(query);
    }

    public PagedDataDTO<ServiceTask> getServiceTasks(@ParameterObject ServiceTaskQuery query) {
        return queryService.findServiceTasks(query);
    }

    public ServiceTask getServiceTask(@PathVariable UUID id) {
        return queryService.getServiceTask(id);
    }

    public PagedDataDTO<UserTask> getUserTasks(@ParameterObject UserTaskQuery query) {
        return queryService.findUserTasks(query);
    }

    public UserTask getUserTask(@PathVariable UUID id) {
        return queryService.getUserTask(id);
    }

    public PagedDataDTO<ProcessInstance> getProcessInstances(@ParameterObject ProcessInstanceQuery query) {
        return queryService.findProcessInstances(query);
    }

    public ProcessInstance getProcessInstance(@PathVariable UUID id) {
        return queryService.getProcessInstance(id);
    }

    public List<ActivityInstance> getProcessInstanceActivities(@PathVariable UUID id) {
        return queryService.getActivities(id);
    }

    public PagedDataDTO<Incident> getIncidents(@ParameterObject IncidentQuery query) {
        return queryService.findIncidents(query);
    }

    public Incident getIncident(@PathVariable UUID id) {
        return queryService.getIncident(id);
    }

    public PagedDataDTO<TimerJob> getTimerJobs(@ParameterObject TimerJobQuery query) {
        return queryService.findTimerJobs(query);
    }

    public PagedDataDTO<MessageSubscription> getMessageSubscriptions(@ParameterObject MessageSubscriptionQuery query) {
        return queryService.findMessageSubscriptions(query);
    }
}
