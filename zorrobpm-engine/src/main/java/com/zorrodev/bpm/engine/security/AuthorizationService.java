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
        DEPLOY, MANAGE_MEMBERS, MANAGE_KEYS, DELETE_PROCESS
    }

    public boolean canOperate(Principal principal, String processDefinitionKey, Action action) {
        if (principal.isSuperAdmin()) return true;
        if (principal instanceof Principal.ServicePrincipal) return true; // SA has pre-approved permissions

        if (principal instanceof Principal.UserPrincipal user) {
            ProcessEntity process = processRepository.findByDefinitionKey(processDefinitionKey).orElse(null);
            if (process == null) return false;
            ProcessMemberEntity membership = processMemberRepository.findById(
                new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), user.userId())).orElse(null);
            if (membership == null) return false;
            return switch (action) {
                case DEPLOY, DELETE_PROCESS -> "OWNER".equals(membership.getRole());
                case MANAGE_MEMBERS -> "OWNER".equals(membership.getRole());
                case MANAGE_KEYS -> "OWNER".equals(membership.getRole());
            };
        }
        return false;
    }

    public boolean canCompleteUserTask(Principal principal, UUID processId) {
        if (principal.isSuperAdmin()) return true;
        // SA with COMPLETE_USER_TASK permission on the right process
        if (principal instanceof Principal.ServicePrincipal sa) {
            return sa.processId().equals(processId) && sa.permissions().contains("COMPLETE_USER_TASK");
        }
        // User: assignee/candidate group check is done separately in RuntimeResource.checkAssignee
        return true;
    }
}
