package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;

    public enum Action {
        // Management actions — SUPER_ADMIN only (ADR-2)
        DEPLOY, MANAGE_MEMBERS, MANAGE_KEYS, DELETE_PROCESS,
        // Runtime actions — SA with grant + correct process
        START, FETCH_LOCK, COMPLETE_SERVICE_TASK, CORRELATE_MESSAGE
    }

    public boolean canOperate(Principal principal, String processDefinitionKey, Action action) {
        if (principal.isSuperAdmin()) return true;

        // ADR-2: SA — management always denied; runtime requires grant
        if (principal instanceof Principal.ServicePrincipal sa) {
            if (isManagementAction(action)) return false;
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            Principal.Grant grant = sa.grants().get(process.getId());
            if (grant == null) return false;
            return grant.isFull() || grant.permissions().contains(action.name());
        }

        // ADR-2: User — management SUPER_ADMIN only; runtime requires membership
        if (principal instanceof Principal.UserPrincipal user) {
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            ProcessMemberEntity membership = processMemberRepository.findById(
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), user.userId())).orElse(null);
            if (membership == null) return false;
            if (isManagementAction(action)) return false;
            return switch (action) {
                case START, FETCH_LOCK, COMPLETE_SERVICE_TASK, CORRELATE_MESSAGE ->
                    "OWNER".equals(membership.getRole()) || "DESIGNER".equals(membership.getRole());
                default -> false;
            };
        }
        return false;
    }

    private boolean isManagementAction(Action action) {
        return action == Action.DEPLOY || action == Action.MANAGE_MEMBERS
            || action == Action.MANAGE_KEYS || action == Action.DELETE_PROCESS;
    }

    public boolean canCompleteUserTask(Principal principal, UUID processInstanceId) {
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
            // WO-AUD-5 F18: cross-tenant check — user must be a process member
            ProcessMemberEntity membership = processMemberRepository.findById(
                new ProcessMemberId(registryProcessId, user.userId())).orElse(null);
            return membership != null;
        }
        return false;
    }

    /**
     * Checks if the principal can claim/unclaim/assign a user task.
     * Mirrors canCompleteUserTask + additionally checks candidate group membership for claim.
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
            // Must be a process member
            ProcessMemberEntity membership = processMemberRepository.findById(
                new ProcessMemberId(registryProcessId, user.userId())).orElse(null);
            if (membership == null) return false;
            return true; // member can claim/assign
        }
        return false;
    }
}
