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
        Collection<UUID> allowedPdIds = resolveAllowedPdIds();
        // WO-IN-2 criterion 4 — and an HONEST boundary statement (red-team HIGH-1, 2026-10-06):
        // this check is a conservative default for the NEW parameter, NOT a security boundary.
        // The access model of this endpoint is per PROCESS (`allowedPdIds`): any participant of a
        // process sees that process's tasks. Person-level visibility does not exist here at all —
        // the pre-existing `assignee` filter is applied without any authorization of its value
        // (UserTaskQueryOperationsImpl.byAssignee), so a participant reaches the very same rows by
        // naming a username, and this guard does not stop that. What it does stop is the ability to
        // ASK the question "tasks that concern person X" — a smaller thing, and the honest name for
        // it is a UX restriction.
        //
        // Rules: your own userId always; a see-all principal (allowedPdIds == null, i.e. SUPER_ADMIN
        // per WO-SEC-54) always — such a caller loses nothing, the unfiltered list already holds
        // those rows. A ServicePrincipal has no user id at all (RuntimeOperationSupport
        // .resolvePrincipalId writes its apiKeyId into `assignee`), so it gets no `relatesTo`
        // support rather than a wrong one.
        //
        // Person-level visibility is WO-ACL-23 (owner's decision), not this WO.
        if (query.getRelatesTo() != null && !mayAskAboutUser(query.getRelatesTo(), allowedPdIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return queryService.findUserTasks(query, allowedPdIds);
    }

    /**
     * WHO MAY ASK ABOUT WHICH USER — see the honest boundary statement in
     * {@link #getUserTasks}: this refuses the QUESTION, it is not a person-level security
     * boundary (`assignee` reaches the same rows). Default DENY (G-L).
     */
    private boolean mayAskAboutUser(UUID targetUserId, Collection<UUID> allowedPdIds) {
        if (allowedPdIds == null) {
            return true; // see-all principal (SUPER_ADMIN)
        }
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal.UserPrincipal user && targetUserId.equals(user.userId());
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
