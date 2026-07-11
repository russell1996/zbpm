package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource implements RuntimeContract {

    private final RuntimeService runtimeService;
    private final UserTaskRepository userTaskRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessRepository processRepository;
    private final IncidentRepository incidentRepository;
    private final ActivityRepository activityRepository;
    private final AuthorizationService authorizationService;
    private final DBService dbService;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireOperate(String definitionKey, AuthorizationService.Action action) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (definitionKey == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        if (!authorizationService.canOperate(principal, definitionKey, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private String resolveDefinitionKeyByInstance(UUID instanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElse(null);
        if (pi == null) return null;
        ProcessDefinitionEntity pd = processDefinitionRepository.findById(pi.getProcessDefinitionId()).orElse(null);
        return pd != null ? pd.getKey() : null;
    }

    private String resolveDefinitionKeyByServiceTask(UUID serviceTaskId) {
        ServiceTaskEntity st = serviceTaskRepository.findById(serviceTaskId).orElse(null);
        if (st == null) return null;
        return resolveDefinitionKeyByInstance(st.getProcessInstanceId());
    }

    private String resolveDefinitionKeyByIncident(UUID incidentId) {
        IncidentEntity incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) return null;
        ActivityEntity activity = activityRepository.findById(incident.getActivityId()).orElse(null);
        if (activity == null) return null;
        return resolveDefinitionKeyByInstance(activity.getProcessInstanceId());
    }

    @Transactional
    @Override
    public IdDTO startProcessInstance(@Valid @RequestBody StartProcessInstanceDTO dto) {
        // Resolve definitionKey from DTO
        String definitionKey = dto.getProcessDefinitionKey();
        if (definitionKey == null && dto.getProcessDefinitionId() != null) {
            ProcessDefinitionEntity pd = processDefinitionRepository.findById(dto.getProcessDefinitionId()).orElse(null);
            if (pd != null) definitionKey = pd.getKey();
        }
        requireOperate(definitionKey, AuthorizationService.Action.START);
        return Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        String key = resolveDefinitionKeyByServiceTask(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        return Optional.ofNullable(runtimeService.completeServiceTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto) {
        String key = resolveDefinitionKeyByServiceTask(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        return Optional.ofNullable(runtimeService.failServiceTask(id, dto.getMessage(), dto.getRetries())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }

        if (!authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Also check assignee (existing check, refactored to use principal)
        checkAssignee(principal, task);

        return Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        // B3: enforce authorization — resolve incident → activity → process → definition_key
        String key = resolveDefinitionKeyByIncident(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        return Optional.ofNullable(runtimeService.resolveIncident(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        var pi = dbService.getProcessInstance(id);
        if (pi.getCompletedAt() != null || pi.isCancelled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Process instance already completed or cancelled");
        }

        String key = resolveDefinitionKeyByInstance(id);
        requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);

        dbService.cancelActiveActivities(id);
        dbService.deleteTimerJobsByProcessInstanceId(id);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(id);
        dbService.cancelProcessInstance(id);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    private void checkAssignee(Principal principal, UserTaskEntity task) {
        if (principal.isSuperAdmin()) return;

        if (principal instanceof Principal.UserPrincipal user) {
            // Unassigned task — any user can complete
            if (task.getAssignee() == null || task.getAssignee().isBlank()) return;
            // Assignee matches — allowed
            if (task.getAssignee().equals(user.username())) return;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Task is assigned to another user");
        }

        // SA: canCompleteUserTask already checked permission + processId
    }

    private IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        IdDTO result = new IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
