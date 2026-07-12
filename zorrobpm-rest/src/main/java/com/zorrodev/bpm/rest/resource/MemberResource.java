package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MemberContract;
import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class MemberResource implements MemberContract {

    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireManageMembers(String processKey) {
        Principal principal = getPrincipal();
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (!authorizationService.canOperate(principal, processKey, AuthorizationService.Action.MANAGE_MEMBERS)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private ProcessEntity resolveProcess(String key) {
        return processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found"));
    }

    private Principal getEffectivePrincipal() {
        return getPrincipal();
    }

    @Override
    public List<MemberDTO> listMembers(@PathVariable String key) {
        requireManageMembers(key);
        ProcessEntity process = resolveProcess(key);
        return processMemberRepository.findByProcessId(process.getId()).stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public MemberDTO addMember(@PathVariable String key, @RequestBody AddMemberDTO dto) {
        requireManageMembers(key);
        ProcessEntity process = resolveProcess(key);

        // Validate user exists
        UiUserEntity user = uiUserRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Check not already a member
        var existing = processMemberRepository.findById(
            new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), dto.getUserId()));
        if (existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member");
        }

        Principal principal = getPrincipal();
        UUID addedBy = null;
        if (principal instanceof Principal.UserPrincipal u) {
            addedBy = u.userId();
        }

        ProcessMemberEntity member = new ProcessMemberEntity();
        member.setProcessId(process.getId());
        member.setUserId(dto.getUserId());
        member.setRole(dto.getRole());
        member.setAddedBy(addedBy);
        member.setAddedAt(Instant.now());
        processMemberRepository.save(member);

        auditLogService.record(getPrincipal(), "MEMBER_ADD", key, dto.getUserId().toString());
        return toDTO(member);
    }

    @Transactional
    @Override
    public MemberDTO changeRole(@PathVariable String key, @PathVariable UUID userId, @RequestBody ChangeRoleDTO dto) {
        requireManageMembers(key);
        ProcessEntity process = resolveProcess(key);

        ProcessMemberEntity member = processMemberRepository.findById(
            new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        member.setRole(dto.getRole());
        processMemberRepository.save(member);
        auditLogService.record(getPrincipal(), "MEMBER_ROLE_CHANGE", key, userId.toString());
        return toDTO(member);
    }

    @Transactional
    @Override
    public IdDTO removeMember(@PathVariable String key, @PathVariable UUID userId) {
        requireManageMembers(key);
        ProcessEntity process = resolveProcess(key);

        ProcessMemberEntity member = processMemberRepository.findById(
            new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        // Cannot remove last OWNER (U2 fail-closed)
        if ("OWNER".equals(member.getRole())) {
            long ownerCount = processMemberRepository.findByProcessId(process.getId()).stream()
                .filter(m -> "OWNER".equals(m.getRole()))
                .count();
            if (ownerCount <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last OWNER");
            }
        }

        processMemberRepository.delete(member);
        auditLogService.record(getPrincipal(), "MEMBER_REMOVE", key, userId.toString());

        IdDTO result = new IdDTO();
        result.setId(userId);
        return result;
    }

    private MemberDTO toDTO(ProcessMemberEntity entity) {
        MemberDTO dto = new MemberDTO();
        dto.setUserId(entity.getUserId());
        dto.setRole(entity.getRole());
        dto.setAddedBy(entity.getAddedBy());
        dto.setAddedAt(entity.getAddedAt());

        // Resolve username
        uiUserRepository.findById(entity.getUserId()).ifPresent(u -> dto.setUsername(u.getUsername()));

        return dto;
    }
}
