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
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final AuditLogService auditLogService;
    private final UserGroupRepository userGroupRepository;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    /** Read optional X-On-Behalf-Of header (WO-INT-2). null if absent. */
    private String readOnBehalfOf() {
        String val = request.getHeader("X-On-Behalf-Of");
        return (val != null && !val.isBlank()) ? val.trim() : null;
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

        String onBehalfOf = readOnBehalfOf();
        IdDTO result = Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(this::toDTO).orElseThrow();

        // WO-INT-2: persist initiator on process instance
        if (onBehalfOf != null) {
            processInstanceRepository.findById(result.getId()).ifPresent(pi -> {
                pi.setInitiator(onBehalfOf);
                processInstanceRepository.save(pi);
            });
        }

        auditLogService.record(getPrincipal(), "START", definitionKey, result.getId().toString(), onBehalfOf);
        return result;
    }

    @Transactional
    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        String key = resolveDefinitionKeyByServiceTask(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.completeServiceTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
        auditLogService.record(getPrincipal(), "COMPLETE_SERVICE_TASK", key, id.toString());
        return result;
    }

    @Transactional
    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto) {
        String key = resolveDefinitionKeyByServiceTask(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.failServiceTask(id, dto.getMessage(), dto.getRetries())).map(this::toDTO).orElseThrow();
        auditLogService.record(getPrincipal(), "FAIL_SERVICE_TASK", key, id.toString());
        return result;
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

        String onBehalfOf = readOnBehalfOf();
        IdDTO result = Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
        auditLogService.record(getPrincipal(), "COMPLETE_USER_TASK", resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(), onBehalfOf);
        return result;
    }

    @Transactional
    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        // B3: enforce authorization — resolve incident → activity → process → definition_key
        String key = resolveDefinitionKeyByIncident(id);
        requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.resolveIncident(id, dto.getVariables())).map(this::toDTO).orElseThrow();
        auditLogService.record(getPrincipal(), "RESOLVE_INCIDENT", key, id.toString());
        return result;
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
        auditLogService.record(getPrincipal(), "CANCEL", key, id.toString());
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    private void checkAssignee(Principal principal, UserTaskEntity task) {
        if (principal.isSuperAdmin()) return;

        if (principal instanceof Principal.UserPrincipal user) {
            // Assignee matches — allowed
            if (task.getAssignee() != null && !task.getAssignee().isBlank()
                    && task.getAssignee().equals(user.username())) return;

            // Member of a candidate group — allowed (WO-MT-3b)
            if (task.getCandidateGroups() != null && !task.getCandidateGroups().isBlank()) {
                Set<String> taskGroups = java.util.Arrays.stream(task.getCandidateGroups().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
                java.util.List<String> userGroups = userGroupRepository.findGroupNamesByUserId(user.userId());
                if (!java.util.Collections.disjoint(taskGroups, userGroups)) return;
            }

            // Unassigned task with no candidate groups — any user can complete
            if ((task.getAssignee() == null || task.getAssignee().isBlank())
                    && (task.getCandidateGroups() == null || task.getCandidateGroups().isBlank())) return;

            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // SA: canCompleteUserTask already checked permission + processId
    }

    private IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        IdDTO result = new IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
