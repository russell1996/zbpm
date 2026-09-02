package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
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
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RuntimeOperationSupport {

    private final HttpServletRequest request;
    private final AuthorizationService authorizationService;
    private final UiUserRepository uiUserRepository;
    private final UserGroupRepository userGroupRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final IncidentRepository incidentRepository;
    private final ActivityRepository activityRepository;

    public Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    /**
     * Read optional X-On-Behalf-Of header (WO-INT-2, WO-SEC-28, WO-INT-4).
     * Returns the raw trimmed value (max 255 chars) or null. The value itself is
     * NOT trusted yet — it must pass {@link #requireOnBehalfMatchesTask} before it
     * is used as an assignee, and audit records keep the "[claimed]" marker.
     */
    public String rawOnBehalfOf() {
        String val = request.getHeader("X-On-Behalf-Of");
        if (val == null || val.isBlank()) return null;
        String trimmed = val.trim();
        if (trimmed.length() > 255) trimmed = trimmed.substring(0, 255);
        return trimmed;
    }

    /**
     * WO-INT-4 criteria 9-10: X-On-Behalf-Of is accepted from ANY key. The rule is about the
     * authentication method, not the account type — a key is a key, whoever it was issued to
     * (WO-INT-4 §3). Returns the raw claimed username, or null when the header is absent.
     */
    public String checkedOnBehalfOf() {
        String raw = rawOnBehalfOf();
        if (raw == null) return null;
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!(principal instanceof Principal.ServicePrincipal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "X-On-Behalf-Of is only accepted from API keys");
        }
        return raw;
    }

    /**
     * WO-INT-4 criteria 9-10: when a service key claims an attribution, the claim must be
     * verifiable — the named user has to be the task assignee or a candidate for it.
     * Otherwise 403. (The trust boundary is unchanged: we trust the system, not its claim.)
     */
    public void requireOnBehalfMatchesTask(UserTaskEntity task, String username) {
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

    public void requireOperate(String definitionKey, AuthorizationService.Action action) {
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
    public ProcessDefinitionEntity resolveTargetDefinition(StartProcessInstanceDTO dto) {
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

    public String resolveDefinitionKeyByInstance(UUID instanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElse(null);
        if (pi == null) return null;
        ProcessDefinitionEntity pd = processDefinitionRepository.findById(pi.getProcessDefinitionId()).orElse(null);
        return pd != null ? pd.getKey() : null;
    }

    public String resolveDefinitionKeyByServiceTask(UUID serviceTaskId) {
        ServiceTaskEntity st = serviceTaskRepository.findById(serviceTaskId).orElse(null);
        if (st == null) return null;
        return resolveDefinitionKeyByInstance(st.getProcessInstanceId());
    }

    public String resolveDefinitionKeyByIncident(UUID incidentId) {
        IncidentEntity incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) return null;
        ActivityEntity activity = activityRepository.findById(incident.getActivityId()).orElse(null);
        if (activity == null) return null;
        return resolveDefinitionKeyByInstance(activity.getProcessInstanceId());
    }

    public String resolvePrincipalId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal user) {
            return user.username();
        }
        if (principal instanceof Principal.ServicePrincipal sa) {
            return sa.apiKeyId().toString();
        }
        return principal.toString();
    }

    public void checkAssignee(Principal principal, UserTaskEntity task) {
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

    public com.zorrodev.bpm.contract.dto.IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        com.zorrodev.bpm.contract.dto.IdDTO result = new com.zorrodev.bpm.contract.dto.IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
