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
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class QueryResource implements QueryContract {

    private final QueryService queryService;
    private final EventAuthzResolver eventAuthzResolver;
    private final HttpServletRequest request;

    /** WO-ARCH-1b: tenant-filtered variables */
    public PagedDataDTO<ProcessVariable> getVariables(@ParameterObject VariableQuery query) {
        return queryService.findVariables(query, resolveAllowedPdIds());
    }

    /** WO-ARCH-1b: tenant-filtered service tasks */
    public PagedDataDTO<ServiceTask> getServiceTasks(@ParameterObject ServiceTaskQuery query) {
        return queryService.findServiceTasks(query, resolveAllowedPdIds());
    }

    public ServiceTask getServiceTask(@PathVariable UUID id) {
        ServiceTask task = queryService.getServiceTask(id);
        requireResourceAccess(task.getProcessDefinitionId());
        return task;
    }

    public PagedDataDTO<UserTask> getUserTasks(@ParameterObject UserTaskQuery query) {
        return queryService.findUserTasks(query, resolveAllowedPdIds());
    }

    public UserTask getUserTask(@PathVariable UUID id) {
        UserTask task = queryService.getUserTask(id);
        requireResourceAccess(task.getProcessDefinitionId());
        return task;
    }

    public PagedDataDTO<ProcessInstance> getProcessInstances(@ParameterObject ProcessInstanceQuery query) {
        return queryService.findProcessInstances(query, resolveAllowedPdIds());
    }

    public ProcessInstance getProcessInstance(@PathVariable UUID id) {
        ProcessInstance instance = queryService.getProcessInstance(id);
        requireResourceAccess(instance.getProcessDefinitionId());
        return instance;
    }

    public List<ActivityInstance> getProcessInstanceActivities(@PathVariable UUID id) {
        ProcessInstance instance = queryService.getProcessInstance(id);
        requireResourceAccess(instance.getProcessDefinitionId());
        return queryService.getActivities(id);
    }

    public PagedDataDTO<ActivityInstance> getProcessInstanceActivitiesPaged(@PathVariable UUID id,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) Integer pageIndex,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) Integer pageSize) {
        ProcessInstance instance = queryService.getProcessInstance(id);
        requireResourceAccess(instance.getProcessDefinitionId());
        return queryService.getActivitiesPaged(id, pageIndex, pageSize);
    }

    /** WO-ARCH-1b: tenant-filtered incidents */
    public PagedDataDTO<Incident> getIncidents(@ParameterObject IncidentQuery query) {
        return queryService.findIncidents(query, resolveAllowedPdIds());
    }

    public Incident getIncident(@PathVariable UUID id) {
        Incident incident = queryService.getIncident(id);
        requireResourceAccess(queryService.resolveIncidentProcessDefinitionId(id));
        return incident;
    }

    /** WO-ARCH-1b: tenant-filtered timer jobs */
    public PagedDataDTO<TimerJob> getTimerJobs(@ParameterObject TimerJobQuery query) {
        return queryService.findTimerJobs(query, resolveAllowedPdIds());
    }

    /** WO-ARCH-1b: tenant-filtered message subscriptions */
    public PagedDataDTO<MessageSubscription> getMessageSubscriptions(@ParameterObject MessageSubscriptionQuery query) {
        return queryService.findMessageSubscriptions(query, resolveAllowedPdIds());
    }

    /**
     * WO-ARCH-1a/1b: resolve allowed processDefinitionIds for tenant isolation.
     * null → see all (admin/SUPER_ADMIN), empty → deny, non-null non-empty → filter.
     */
    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.readableRuntimePdIds(principal, null);
    }

    /**
     * WO-SEC-43: 404 (not 403) when the current principal has no grant on the owning
     * process definition of a singular query resource. null allowed → full access
     * (superAdmin / full-grant); otherwise the owning pdId must be in the allowed set.
     * 404 keeps existence of the foreign resource hidden (same pattern as WO-SEC-40).
     */
    private void requireResourceAccess(UUID owningPdId) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed == null) {
            return;
        }
        if (owningPdId == null || !allowed.contains(owningPdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
    }
}
