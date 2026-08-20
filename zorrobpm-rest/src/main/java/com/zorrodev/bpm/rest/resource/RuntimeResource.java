package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.FormValidationException;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
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
    private final FormArtifactService formArtifactService;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    /**
     * Read optional X-On-Behalf-Of header (WO-INT-2, WO-SEC-28, WO-INT-4).
     * Returns the raw trimmed value (max 255 chars) or null. The value itself is
     * NOT trusted yet — it must pass {@link #requireOnBehalfMatchesTask} before it
     * is used as an assignee, and audit records keep the "[claimed]" marker.
     */
    private String rawOnBehalfOf() {
        String val = request.getHeader("X-On-Behalf-Of");
        if (val == null || val.isBlank()) return null;
        String trimmed = val.trim();
        if (trimmed.length() > 255) trimmed = trimmed.substring(0, 255);
        return trimmed;
    }

    /**
     * WO-INT-4 criteria 11-12: X-On-Behalf-Of is accepted ONLY from system accounts
     * (service keys). A human key acting "on behalf of another" is impersonation, not
     * integration — 403. Returns the raw claimed username, or null when the header is absent.
     */
    private String checkedOnBehalfOf() {
        String raw = rawOnBehalfOf();
        if (raw == null) return null;
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!(principal instanceof Principal.ServicePrincipal sp)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "X-On-Behalf-Of is only accepted from system accounts");
        }
        // WO-INT-4 criterion 12: the KEY ITSELF must belong to a system account.
        // A human-owned key claiming "on behalf of" is impersonation, not integration —
        // the principal type alone cannot tell them apart (both are ServicePrincipal).
        UiUserEntity owner = uiUserRepository.findById(sp.ownerUserId()).orElse(null);
        if (owner == null || !"SYSTEM".equals(owner.getUserType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "X-On-Behalf-Of is only accepted from system accounts");
        }
        return raw;
    }

    /**
     * WO-INT-4 criterion 11: when a service key claims an attribution, the claim must be
     * verifiable — the named user has to be the task assignee or a candidate for it.
     * Otherwise 403. (The trust boundary is unchanged: we trust the system, not its claim.)
     */
    private void requireOnBehalfMatchesTask(UserTaskEntity task, String username) {
        UiUserEntity namedUser = uiUserRepository.findByUsername(username).orElse(null);
        if (namedUser == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Assigned task: the claimed user must BE the assignee.
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
            if (task.getAssignee().equals(username)) return;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Unassigned task with candidate groups: the claimed user must belong to one of them.
        if (task.getCandidateGroups() != null && !task.getCandidateGroups().isBlank()) {
            Set<String> taskGroups = java.util.Arrays.stream(task.getCandidateGroups().split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
            java.util.List<String> userGroups = userGroupRepository.findGroupNamesByUserId(namedUser.getId());
            if (!java.util.Collections.disjoint(taskGroups, userGroups)) return;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Unassigned task with no candidate groups: open to process members only.
        ProcessInstanceEntity instance = processInstanceRepository.findById(task.getProcessInstanceId()).orElse(null);
        if (instance != null) {
            ProcessDefinitionEntity definition = processDefinitionRepository.findById(instance.getProcessDefinitionId()).orElse(null);
            if (definition != null) {
                ProcessEntity process = processRepository.findByDefinitionKey(definition.getKey()).orElse(null);
                if (process != null) {
                    ProcessMemberEntity membership = processMemberRepository.findById(
                        new ProcessMemberId(process.getId(), namedUser.getId())).orElse(null);
                    if (membership != null) return;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    /** WO-INT-4 criterion 5: a human task must never be assigned to a system account. */
    private void requireHumanAssignee(String assignee) {
        if (assignee == null || assignee.isBlank()) return;
        uiUserRepository.findByUsername(assignee)
            .filter(u -> "SYSTEM".equals(u.getUserType()))
            .ifPresent(u -> {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot assign a user task to a system account");
            });
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

    /**
     * WO-ENG-10: resolves the EXACT {@link ProcessDefinitionEntity} that
     * {@code RuntimeServiceImpl.startProcessInstance} will actually start — same order:
     * explicit {@code processDefinitionId} wins; else {@code processDefinitionKey} +
     * {@code processDefinitionVersion} (or max version by key if version is unset). Returns
     * null only if the DTO fails validation elsewhere (id/key both absent) or the resolved
     * definition genuinely doesn't exist (startProcessInstance will itself throw in that case).
     */
    private ProcessDefinitionEntity resolveTargetDefinition(StartProcessInstanceDTO dto) {
        if (dto.getProcessDefinitionId() != null) {
            return processDefinitionRepository.findById(dto.getProcessDefinitionId()).orElse(null);
        }
        String key = dto.getProcessDefinitionKey();
        if (key == null) {
            return null;
        }
        Integer version = dto.getProcessDefinitionVersion();
        if (version == null) {
            version = processDefinitionRepository.findMaxByKey(key).orElse(null);
        }
        if (version == null) {
            return null;
        }
        return processDefinitionRepository.findByKeyAndVersion(key, version).orElse(null);
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

        // WO-ENG-10: validate against the EXACT definition that will actually be started, not
        // always "latest by key" — mirrors RuntimeServiceImpl.startProcessInstance's own
        // resolution order (id > key+version > key+maxVersion), so a start pinned to an older
        // version is validated against that version's form/schema, not a newer one's.
        ProcessDefinitionEntity targetDefinition = resolveTargetDefinition(dto);

        // ADR-6 §D9: form validation via FormArtifactService facade
        if (targetDefinition != null && targetDefinition.getStartFormKey() != null) {
            List<FormValidator.ValidationError> errors = formArtifactService.validateFormIfApplicable(
                targetDefinition.getStartFormKey(), dto.getVariables());
            if (!errors.isEmpty()) {
                String errorDetails = errors.stream()
                    .map(e -> e.field() + ": " + e.message())
                    .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorDetails);
            }
        }

        String onBehalfOf = checkedOnBehalfOf();
        IdDTO result = Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(this::toDTO).orElseThrow();

        // WO-INT-2: persist initiator on process instance
        if (onBehalfOf != null) {
            // WO-SEC-28: the initiator is a claimed, unverified attribution — keep the marker.
            String claimed = "[claimed] " + onBehalfOf;
            processInstanceRepository.findById(result.getId()).ifPresent(pi -> {
                pi.setInitiator(claimed);
                processInstanceRepository.save(pi);
            });
            auditLogService.record(getPrincipal(), "START", definitionKey, result.getId().toString(), claimed);
        } else {
            auditLogService.record(getPrincipal(), "START", definitionKey, result.getId().toString(), null);
        }
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

        if (!authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Also check assignee (existing check, refactored to use principal)
        checkAssignee(principal, task);

        // ADR-6 §D9: form validation via FormArtifactService facade
        if (dto.getVariables() != null && !dto.getVariables().isEmpty()) {
            List<FormValidator.ValidationError> errors = formArtifactService.validateFormIfApplicable(
                task.getFormKey(), dto.getVariables());
            if (!errors.isEmpty()) {
                throw new FormValidationException(errors);
            }
        }

        String onBehalfOf = checkedOnBehalfOf();
        // WO-INT-4 criterion 11: a service key's attribution claim must be verifiable —
        // the named user has to be the assignee or a candidate for this task.
        if (onBehalfOf != null) {
            requireOnBehalfMatchesTask(task, onBehalfOf);
        }
        IdDTO result = Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
        auditLogService.record(getPrincipal(), "COMPLETE_USER_TASK", resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(),
            onBehalfOf != null ? "[claimed] " + onBehalfOf : null);
        return result;
    }

    @Transactional
    @Override
    public IdDTO claimUserTask(@PathVariable UUID id) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }
        if (task.getAssignee() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already assigned");
        }

        if (!authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Assignee: X-On-Behalf-Of header (verified against candidates — WO-INT-4),
        // otherwise principal id
        String assignee = checkedOnBehalfOf();
        if (assignee == null || assignee.isBlank()) {
            assignee = resolvePrincipalId(principal);
        } else {
            // WO-INT-4 criterion 11: the claimed user must be a candidate for this task.
            // (An unassigned task cannot match by assignee, so candidate/process-member
            // rules apply — the same rules as canClaimUserTask for a real user.)
            requireOnBehalfMatchesTask(task, assignee);
        }

        // Atomic claim can still lose the race to a concurrent claimant between the check above
        // and the update → surface as 409, not a 500.
        try {
            dbService.claimUserTask(id, assignee);
        } catch (IllegalStateException e) {
            log.warn("Failed to claim user task {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed to claim user task");
        }
        auditLogService.record(getPrincipal(), "CLAIM_USER_TASK", resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(), assignee);

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Transactional
    @Override
    public IdDTO unclaimUserTask(@PathVariable UUID id) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }

        if (!authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        dbService.unclaimUserTask(id);
        auditLogService.record(getPrincipal(), "UNCLAIM_USER_TASK", resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString());

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Transactional
    @Override
    public IdDTO assignUserTask(@PathVariable UUID id, @RequestBody AssignUserTaskDTO dto) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        if (dto == null || dto.getAssignee() == null || dto.getAssignee().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignee is required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }

        // Reassign is administrative: requires owner/admin (stricter than claim's candidate check).
        if (!authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // WO-INT-4 criterion 5: a human task assigned to a system account would never be
        // executed and would appear in nobody's inbox — reject the assignment.
        requireHumanAssignee(dto.getAssignee());

        dbService.assignUserTask(id, dto.getAssignee());
        auditLogService.record(getPrincipal(), "ASSIGN_USER_TASK", resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(), dto.getAssignee());

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    private String resolvePrincipalId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal user) {
            return user.username();
        }
        if (principal instanceof Principal.ServicePrincipal sa) {
            return sa.apiKeyId().toString();
        }
        return principal.toString();
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

            // WO-SEC-56: task is personally assigned to someone else → forbidden even for
            // candidate-group members (candidate pool applies only while the task is unassigned)
            if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }

            // Member of a candidate group — allowed (WO-MT-3b)
            if (task.getCandidateGroups() != null && !task.getCandidateGroups().isBlank()) {
                Set<String> taskGroups = java.util.Arrays.stream(task.getCandidateGroups().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
                java.util.List<String> userGroups = userGroupRepository.findGroupNamesByUserId(user.userId());
                if (!java.util.Collections.disjoint(taskGroups, userGroups)) return;
            }

            // Unassigned task with no candidate groups — only process members can complete (WO-AUD-5 F18)
            if ((task.getAssignee() == null || task.getAssignee().isBlank())
                    && (task.getCandidateGroups() == null || task.getCandidateGroups().isBlank())) {
                // Resolve instance → definition → key → registry → membership
                ProcessInstanceEntity instance = processInstanceRepository.findById(task.getProcessInstanceId()).orElse(null);
                if (instance != null) {
                    ProcessDefinitionEntity definition = processDefinitionRepository.findById(instance.getProcessDefinitionId()).orElse(null);
                    if (definition != null) {
                        ProcessEntity process = processRepository.findByDefinitionKey(definition.getKey()).orElse(null);
                        if (process != null) {
                            ProcessMemberEntity membership = processMemberRepository.findById(
                                new ProcessMemberId(process.getId(), user.userId())).orElse(null);
                            if (membership != null) return; // member can complete
                        }
                    }
                }
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }

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
