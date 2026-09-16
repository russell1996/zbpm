package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final UserGroupRepository userGroupRepository;

    public enum Action {
        // Management actions — SUPER_ADMIN only (ADR-2), unless granted to a process role (ADR-8 п.7)
        DEPLOY, MANAGE_MEMBERS, MANAGE_KEYS, DELETE_PROCESS,
        // Runtime actions — SA with grant + correct process
        START, FETCH_LOCK, COMPLETE_SERVICE_TASK, CORRELATE_MESSAGE,
        // WO-INT-4: user-task operations (complete/claim/reassign on behalf of a verified
        // user) — granted to the OWNER role so a system key with a full grant can drive them
        COMPLETE_USER_TASK,
        // Read action: process member list (ADR-8 п.4 — members are visible to members of the process)
        VIEW_MEMBERS
    }

    /**
     * Process role → allowed actions (ADR-8 п.4: fixed mapping, defined in code).
     * <ul>
     *   <li>OWNER — everything process-scoped: runtime, DEPLOY (model update, ADR-8 п.3), MANAGE_MEMBERS (п.7).</li>
     *   <li>DESIGNER — runtime + DEPLOY (model update, ADR-8 п.3); members are managed by OWNER only (п.7).</li>
     *   <li>VIEWER — read-only: may see members and their roles (ADR-8 п.4).</li>
     * </ul>
     */
    private static final Map<ProcessRole, Set<Action>> ROLE_RIGHTS = Map.of(
        ProcessRole.OWNER, EnumSet.of(Action.DEPLOY, Action.MANAGE_MEMBERS, Action.VIEW_MEMBERS,
            Action.START, Action.FETCH_LOCK, Action.COMPLETE_SERVICE_TASK, Action.CORRELATE_MESSAGE,
            // WO-INT-4 criterion 4: a system key owned by an OWNER completes/claims user tasks
            // on behalf of a verified user — "runtime" rights of the owner role.
            Action.COMPLETE_USER_TASK),
        ProcessRole.DESIGNER, EnumSet.of(Action.DEPLOY, Action.VIEW_MEMBERS,
            Action.START, Action.FETCH_LOCK, Action.COMPLETE_SERVICE_TASK, Action.CORRELATE_MESSAGE),
        ProcessRole.VIEWER, EnumSet.of(Action.VIEW_MEMBERS)
    );

    public boolean canOperate(Principal principal, String processDefinitionKey, Action action) {
        if (principal.isSuperAdmin()) return true;

        // WO-INT-4: one authorization model — a service key is just a key, its rights come from
        // the same grant/role intersection as any other key (effectiveGrants). No type-based
        // special cases here: "Системе не дают роль владельца — и она не управляет участниками"
        // is policy, not a guard.
        if (principal instanceof Principal.ServicePrincipal sa) {
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            Principal.Grant grant = sa.grants().get(process.getId());
            if (grant == null) return false;
            return grant.isFull() || grant.permissions().contains(action.name());
        }

        // ADR-8: User — rights come from the process role (DB, not token); membership required
        if (principal instanceof Principal.UserPrincipal user) {
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            ProcessMemberEntity membership = processMemberRepository.findById(
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), user.userId())).orElse(null);
            if (membership == null) return false;
            ProcessRole role = ProcessRole.fromName(membership.getRole());
            if (role == null) return false; // unknown role in DB → deny, never a silent fallback (G-L)
            return ROLE_RIGHTS.get(role).contains(action);
        }
        return false;
    }

    /**
     * WO-ACL-5 (ADR-8 п.5): effective rights of a service key are the INTERSECTION of
     * the key's grants and the owner's CURRENT process rights — recomputed on every
     * request, never frozen at issue time.
     * <ul>
     *   <li>process no longer exists / owner lost membership / unknown role → grant DROPPED
     *       (the process disappears from the key's visibility — DENY, G-L);</li>
     *   <li>full grant → narrowed to the role's action set (full never exceeds the owner);</li>
     *   <li>partial grant → action permissions are intersected with the role's action set.
     *       Non-action markers (e.g. "READ") are dropped from permissions, but the grant
     *       itself survives with empty permissions: read visibility is keySet-driven
     *       (EventAuthzResolver.resolveByGrants), so a member-owner keeps seeing the
     *       process data while gaining no runtime rights (START etc. stay DENY).</li>
     * </ul>
     * Reuses the ROLE_RIGHTS mapping — the single source of process-role rights (P-24).
     */
    public Map<UUID, Principal.Grant> effectiveGrants(UUID ownerUserId, Map<UUID, Principal.Grant> keyGrants) {
        if (keyGrants == null || keyGrants.isEmpty()) return Map.of();

        Set<UUID> ids = keyGrants.keySet();
        Map<UUID, ProcessEntity> processMap = processRepository.findByIdIn(ids).stream()
            .collect(Collectors.toMap(ProcessEntity::getId, p -> p));
        Map<UUID, ProcessMemberEntity> memberMap = processMemberRepository
            .findByProcessIdInAndUserId(ids, ownerUserId).stream()
            .collect(Collectors.toMap(ProcessMemberEntity::getProcessId, m -> m));

        Map<UUID, Principal.Grant> effective = new HashMap<>();
        for (Map.Entry<UUID, Principal.Grant> entry : keyGrants.entrySet()) {
            UUID processId = entry.getKey();
            Principal.Grant grant = entry.getValue();

            ProcessEntity process = processMap.get(processId);
            if (process == null) continue;

            ProcessMemberEntity membership = memberMap.get(processId);
            if (membership == null) continue;

            ProcessRole role = ProcessRole.fromName(membership.getRole());
            if (role == null) continue;

            Set<String> rolePermissions = ROLE_RIGHTS.get(role).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

            Set<String> narrowed;
            if (grant.isFull()) {
                narrowed = rolePermissions;
            } else {
                Set<String> granted = grant.permissions() != null ? grant.permissions() : Set.of();
                narrowed = granted.stream().filter(rolePermissions::contains).collect(Collectors.toSet());
            }

            effective.put(processId, new Principal.Grant(narrowed, false));
        }
        return effective;
    }

    public boolean canCompleteUserTask(Principal principal, UUID processInstanceId) {
        return canCompleteUserTask(principal, processInstanceId, null);
    }

    /**
     * Checks if the principal can complete a user task. Authz default is DENY.
     * <ul>
     *   <li>SUPER_ADMIN — allowed.</li>
     *   <li>ServicePrincipal — needs a grant with COMPLETE_USER_TASK (or full) on the process.</li>
     *   <li>UserPrincipal, task HAS candidateGroups — allowed only if the user belongs to one of them.</li>
     *   <li>UserPrincipal, task has NO candidateGroups — allowed only if the user is a process member.</li>
     * </ul>
     */
    public boolean canCompleteUserTask(Principal principal, UUID processInstanceId, String candidateGroups) {
        if (principal.isSuperAdmin()) return true;

        // Resolve instance → definition → key → registry processId
        ProcessInstanceEntity instance = processInstanceRepository.findById(processInstanceId).orElse(null);
        if (instance == null) return false;
        ProcessDefinitionEntity definition = processDefinitionRepository.findById(instance.getProcessDefinitionId()).orElse(null);
        if (definition == null) return false;
        ProcessEntity process = processRepository.findByDefinitionKey(definition.getKey()).orElse(null);
        if (process == null) return false;
        UUID registryProcessId = process.getId();

        if (principal instanceof Principal.ServicePrincipal sa) {
            Principal.Grant grant = sa.grants().get(registryProcessId);
            if (grant == null) return false;
            return grant.isFull() || grant.permissions().contains("COMPLETE_USER_TASK");
        }
        if (principal instanceof Principal.UserPrincipal user) {
            Set<String> taskGroups = parseCandidateGroups(candidateGroups);
            if (!taskGroups.isEmpty()) {
                // Task restricted to candidate groups → user must be in one of them.
                List<String> userGroups = userGroupRepository.findGroupNamesByUserId(user.userId());
                return userGroups.stream().anyMatch(taskGroups::contains);
            }
            // No candidate groups → open to process members only (cross-tenant guard).
            return processMemberRepository.findById(
                new ProcessMemberId(registryProcessId, user.userId())).orElse(null) != null;
        }
        return false;
    }

    /**
     * Checks if the principal can claim/unclaim/assign a user task. Authz default is DENY.
     * <ul>
     *   <li>SUPER_ADMIN — allowed.</li>
     *   <li>ServicePrincipal — needs a grant with COMPLETE_USER_TASK (or full) on the process.</li>
     *   <li>UserPrincipal, task HAS candidateGroups — allowed only if the user belongs to one of them
     *       (candidate eligibility, mirrors Camunda). A process member who is not a candidate is denied.</li>
     *   <li>UserPrincipal, task has NO candidateGroups — open to the team: allowed only if the user is a
     *       process member (blocks cross-tenant claims).</li>
     * </ul>
     */
    public boolean canClaimUserTask(Principal principal, UUID processInstanceId, String candidateGroups) {
        if (principal.isSuperAdmin()) return true;

        // Resolve instance → definition → key → registry processId
        ProcessInstanceEntity instance = processInstanceRepository.findById(processInstanceId).orElse(null);
        if (instance == null) return false;
        ProcessDefinitionEntity definition = processDefinitionRepository.findById(instance.getProcessDefinitionId()).orElse(null);
        if (definition == null) return false;
        ProcessEntity process = processRepository.findByDefinitionKey(definition.getKey()).orElse(null);
        if (process == null) return false;
        UUID registryProcessId = process.getId();

        if (principal instanceof Principal.ServicePrincipal sa) {
            Principal.Grant grant = sa.grants().get(registryProcessId);
            if (grant == null) return false;
            return grant.isFull() || grant.permissions().contains("COMPLETE_USER_TASK");
        }
        if (principal instanceof Principal.UserPrincipal user) {
            Set<String> taskGroups = parseCandidateGroups(candidateGroups);
            if (!taskGroups.isEmpty()) {
                // Task restricted to candidate groups → user must be in one of them.
                List<String> userGroups = userGroupRepository.findGroupNamesByUserId(user.userId());
                return userGroups.stream().anyMatch(taskGroups::contains);
            }
            // No candidate groups → open to process members only (cross-tenant guard).
            return processMemberRepository.findById(
                new ProcessMemberId(registryProcessId, user.userId())).orElse(null) != null;
        }
        return false;
    }

    /**
     * Checks if the principal can REASSIGN a user task to someone else (administrative action).
     * Stricter than claim: default DENY, candidate membership is NOT enough.
     * <ul>
     *   <li>SUPER_ADMIN — allowed.</li>
     *   <li>ServicePrincipal — needs a grant with COMPLETE_USER_TASK (or full) on the process.</li>
     *   <li>UserPrincipal — only a process OWNER/DESIGNER (a plain member/candidate is denied).</li>
     * </ul>
     */
    public boolean canReassignUserTask(Principal principal, UUID processInstanceId) {
        if (principal.isSuperAdmin()) return true;

        ProcessInstanceEntity instance = processInstanceRepository.findById(processInstanceId).orElse(null);
        if (instance == null) return false;
        ProcessDefinitionEntity definition = processDefinitionRepository.findById(instance.getProcessDefinitionId()).orElse(null);
        if (definition == null) return false;
        ProcessEntity process = processRepository.findByDefinitionKey(definition.getKey()).orElse(null);
        if (process == null) return false;
        UUID registryProcessId = process.getId();

        if (principal instanceof Principal.ServicePrincipal sa) {
            Principal.Grant grant = sa.grants().get(registryProcessId);
            if (grant == null) return false;
            return grant.isFull() || grant.permissions().contains("COMPLETE_USER_TASK");
        }
        if (principal instanceof Principal.UserPrincipal user) {
            ProcessMemberEntity membership = processMemberRepository.findById(
                new ProcessMemberId(registryProcessId, user.userId())).orElse(null);
            if (membership == null) return false;
            ProcessRole role = ProcessRole.fromName(membership.getRole());
            return role == ProcessRole.OWNER || role == ProcessRole.DESIGNER;
        }
        return false;
    }

    /** Parses a comma-separated candidate-groups string into a trimmed, non-blank set. */
    private static Set<String> parseCandidateGroups(String candidateGroups) {
        if (candidateGroups == null || candidateGroups.isBlank()) return Set.of();
        return Arrays.stream(candidateGroups.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }
}
