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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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

    public PagedDataDTO<ProcessVariable> getVariables(@ParameterObject VariableQuery query) {
        return queryService.findVariables(query);
    }

    public PagedDataDTO<ServiceTask> getServiceTasks(@ParameterObject ServiceTaskQuery query) {
        return queryService.findServiceTasks(query);
    }

    public ServiceTask getServiceTask(@PathVariable UUID id) {
        return queryService.getServiceTask(id);
    }

    /** WO-ARCH-1a: tenant-filtered user tasks */
    public PagedDataDTO<UserTask> getUserTasks(@ParameterObject UserTaskQuery query) {
        return queryService.findUserTasks(query, resolveAllowedPdIds());
    }

    public UserTask getUserTask(@PathVariable UUID id) {
        return queryService.getUserTask(id);
    }

    /** WO-ARCH-1a: tenant-filtered process instances */
    public PagedDataDTO<ProcessInstance> getProcessInstances(@ParameterObject ProcessInstanceQuery query) {
        return queryService.findProcessInstances(query, resolveAllowedPdIds());
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

    /**
     * WO-ARCH-1a: resolve allowed processDefinitionIds for tenant isolation.
     * null → see all (admin/SUPER_ADMIN), empty → deny, non-null non-empty → filter.
     * Reuses EventAuthzResolver (G-L: no duplicate membership logic).
     */
    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of(); // no principal → deny
        }
        // null key = resolve ALL allowed pdIds (not filtered by processDefinitionKey)
        return eventAuthzResolver.resolve(principal, null);
    }
}
