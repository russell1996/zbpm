package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.contract.model.UserTask;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;
import java.util.UUID;

public interface QueryContract {

    @GetExchange("/variables")
    PagedDataDTO<ProcessVariable> getVariables(VariableQuery query);

    @GetExchange("/service-tasks")
    PagedDataDTO<ServiceTask> getServiceTasks(ServiceTaskQuery query);

    @GetExchange("/service-tasks/{id}")
    ServiceTask getServiceTask(@PathVariable UUID id);

    @GetExchange("/user-tasks")
    PagedDataDTO<UserTask> getUserTasks(UserTaskQuery query);

    @GetExchange("/user-tasks/{id}")
    UserTask getUserTask(@PathVariable UUID id);

    @GetExchange("/process-instances")
    PagedDataDTO<ProcessInstance> getProcessInstances(ProcessInstanceQuery query);

    @GetExchange("/process-instances/{id}")
    ProcessInstance getProcessInstance(@PathVariable UUID id);

    /** Activity history of an instance (used for execution history and BPMN element highlighting). */
    @GetExchange("/process-instances/{id}/activities")
    List<ActivityInstance> getProcessInstanceActivities(@PathVariable UUID id);

    /** WO-PERF-7: paged activity history (bounded; the legacy List endpoint above stays for compat). */
    @GetExchange("/process-instances/{id}/activities/paged")
    PagedDataDTO<ActivityInstance> getProcessInstanceActivitiesPaged(@PathVariable UUID id,
        @RequestParam(required = false) Integer pageIndex,
        @RequestParam(required = false) Integer pageSize);

    @GetExchange("/incidents")
    PagedDataDTO<Incident> getIncidents(IncidentQuery query);

    @GetExchange("/incidents/{id}")
    Incident getIncident(@PathVariable UUID id);

    @GetExchange("/timer-jobs")
    PagedDataDTO<TimerJob> getTimerJobs(TimerJobQuery query);

    @GetExchange("/message-subscriptions")
    PagedDataDTO<MessageSubscription> getMessageSubscriptions(MessageSubscriptionQuery query);

}
