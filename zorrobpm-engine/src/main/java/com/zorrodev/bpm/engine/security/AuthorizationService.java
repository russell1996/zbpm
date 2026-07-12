package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
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

    public enum Action {
        // Management actions — OWNER only, SA never
        DEPLOY, MANAGE_MEMBERS, MANAGE_KEYS, DELETE_PROCESS,
        // Runtime actions — SA with permission + correct process
        START, FETCH_LOCK, COMPLETE_SERVICE_TASK, CORRELATE_MESSAGE
    }

    public boolean canOperate(Principal principal, String processDefinitionKey, Action action) {
        if (principal.isSuperAdmin()) return true;

        // SA: management actions always denied; runtime actions require permission + process scope
        if (principal instanceof Principal.ServicePrincipal sa) {
            if (isManagementAction(action)) return false;
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            if (!sa.processId().equals(process.getId())) return false;
            return sa.permissions().contains(action.name());
        }

        if (principal instanceof Principal.UserPrincipal user) {
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            ProcessMemberEntity membership = processMemberRepository.findById(
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), user.userId())).orElse(null);
            if (membership == null) return false;
            // ADR-2: management actions (DEPLOY/MANAGE_MEMBERS/MANAGE_KEYS/DELETE_PROCESS) → SUPER_ADMIN only
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

    public boolean canCompleteUserTask(Principal principal, UUID processId) {
        if (principal.isSuperAdmin()) return true;
        if (principal instanceof Principal.ServicePrincipal sa) {
            return sa.processId().equals(processId) && sa.permissions().contains("COMPLETE_USER_TASK");
        }
        // User: assignee/candidate group check is done separately in RuntimeResource.checkAssignee
        return true;
    }
}
