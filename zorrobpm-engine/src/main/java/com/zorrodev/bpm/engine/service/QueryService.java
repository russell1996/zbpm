package com.zorrodev.bpm.engine.service;

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

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QueryService {

    /** Activity history of an instance (ascending by creation), for execution history + BPMN highlight. */
    List<ActivityInstance> getActivities(UUID processInstanceId);

    PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query);

    ServiceTask getServiceTask(UUID id);

    /** WO-ARCH-1a: allowedPdIds = null → see all (admin); non-null → filter; empty → deny. */
    PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds);

    UserTask getUserTask(UUID id);

    ProcessInstance getProcessInstance(UUID id);

    /** WO-ARCH-1a: allowedPdIds = null → see all (admin); non-null → filter; empty → deny. */
    PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds);

    Incident getIncident(UUID id);

    PagedDataDTO<Incident> findIncidents(IncidentQuery query);

    PagedDataDTO<ProcessVariable> findVariables(VariableQuery query);

    PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query);

    PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query);
}
