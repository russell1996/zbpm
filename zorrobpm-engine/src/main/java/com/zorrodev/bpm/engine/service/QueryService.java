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

    List<ActivityInstance> getActivities(UUID processInstanceId);

    PagedDataDTO<ActivityInstance> getActivitiesPaged(UUID processInstanceId, Integer pageIndex, Integer pageSize);

    /** WO-ARCH-1b: allowedPdIds = null → see all; non-null → filter; empty → deny. */
    PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query, Collection<UUID> allowedPdIds);

    ServiceTask getServiceTask(UUID id);

    PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds);

    UserTask getUserTask(UUID id);

    ProcessInstance getProcessInstance(UUID id);

    PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds);

    Incident getIncident(UUID id);

    /**
     * WO-SEC-43: resolve the processDefinitionId owning an incident (via its
     * activity → process instance), or {@code null} if the incident does not exist.
     */
    UUID resolveIncidentProcessDefinitionId(UUID incidentId);

    PagedDataDTO<Incident> findIncidents(IncidentQuery query, Collection<UUID> allowedPdIds);

    PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds);

    PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query, Collection<UUID> allowedPdIds);

    PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query, Collection<UUID> allowedPdIds);
}
