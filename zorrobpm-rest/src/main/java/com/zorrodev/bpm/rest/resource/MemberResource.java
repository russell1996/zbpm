package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MemberContract;
import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberCandidateDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class MemberResource implements MemberContract {

    /**
     * WO-ACL-7: minimum username fragment for the candidates search. Below this the
     * query would match almost everything and the endpoint would act as a directory.
     */
    static final int MIN_CANDIDATE_QUERY_LENGTH = 3;

    /** Hard cap on the candidate result set — never the whole user table. */
    static final int MAX_CANDIDATES = 20;

    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGrantRepository apiKeyGrantRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireOperate(String processKey, AuthorizationService.Action action) {
        Principal principal = getPrincipal();
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (!authorizationService.canOperate(principal, processKey, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }

    /** WO-ACL-7: memberships are a USER self-service — API keys must not see the owner's memberships. */
    private Principal.UserPrincipal requireUserPrincipal() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Memberships are a user self-service — API keys cannot use this endpoint");
        }
        return u;
    }

    private ProcessEntity resolveProcess(String key) {
        return processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found"));
    }

    /**
     * WO-ACL-2: the invariant "a process always has at least one OWNER" is enforced in ONE place
     * for both removeMember and changeRole. Previously only removeMember had it — demoting the
     * single OWNER through changeRole left the process ownerless.
     */
    private void requireOwnerRemains(ProcessEntity process, ProcessMemberEntity member, ProcessRole targetRole) {
        if (ProcessRole.fromName(member.getRole()) != ProcessRole.OWNER) return;
        if (targetRole == ProcessRole.OWNER) return; // OWNER → OWNER keeps the invariant
        long ownerCount = processMemberRepository.findByProcessId(process.getId()).stream()
            .filter(m -> ProcessRole.fromName(m.getRole()) == ProcessRole.OWNER)
            .count();
        if (ownerCount <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove or demote the last OWNER");
        }
    }

    /** Unknown/absent role → 400 with the list of valid roles (WO-ACL-2 п.1), not a rightless member. */
    private ProcessRole requireValidRole(ProcessRole role) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid role. Valid roles: " + ProcessRole.validRolesDescription());
        }
        return role;
    }

    @Override
    public List<MemberDTO> listUserMemberships(UUID userId) {
        requireSuperAdmin();
        return processMemberRepository.findByUserId(userId).stream()
            .map(m -> {
                MemberDTO dto = toDTO(m);
                processRepository.findById(m.getProcessId())
                    .ifPresent(p -> dto.setProcessKey(p.getDefinitionKey()));
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * WO-ACL-7 criterion 1: the caller's OWN memberships only — the userId comes from the
     * authenticated principal, never from a path/query parameter. "My memberships" is
     * self-data: no cross-user access, no admin directory leak.
     */
    @Override
    public List<MemberDTO> listMyMemberships() {
        Principal.UserPrincipal user = requireUserPrincipal();
        return processMemberRepository.findByUserId(user.userId()).stream()
            .map(m -> {
                MemberDTO dto = toDTO(m);
                processRepository.findById(m.getProcessId())
                    .ifPresent(p -> dto.setProcessKey(p.getDefinitionKey()));
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * WO-ACL-7 (ADR-8 п.7): who can be ADDED to this process. OWNER-scoped candidate
     * search: MANAGE_MEMBERS on the process, a mandatory non-empty {@code q} (min 3 chars —
     * an empty query would return the user table), active users only, members excluded,
     * result capped. Output is deliberately minimal: userId + username.
     */
    @Override
    public List<MemberCandidateDTO> candidateMembers(@PathVariable String key, String q) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        ProcessEntity process = resolveProcess(key);

        String query = q == null ? "" : q.trim();
        if (query.length() < MIN_CANDIDATE_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Search query 'q' must be at least " + MIN_CANDIDATE_QUERY_LENGTH + " characters");
        }

        Set<UUID> memberIds = processMemberRepository.findByProcessId(process.getId()).stream()
            .map(ProcessMemberEntity::getUserId)
            .collect(Collectors.toSet());

        List<Specification<UiUserEntity>> specs = new ArrayList<>();
        specs.add(UiUserRepository.byUsernameContains(query));
        specs.add(UiUserRepository.byActive(true));
        if (!memberIds.isEmpty()) {
            specs.add((root, cbq, cb) -> cb.not(root.get("id").in(memberIds)));
        }

        return uiUserRepository.findAll(Specification.allOf(specs),
                PageRequest.of(0, MAX_CANDIDATES, Sort.by("username").ascending()))
            .getContent().stream()
            .map(u -> {
                MemberCandidateDTO dto = new MemberCandidateDTO();
                dto.setUserId(u.getId());
                dto.setUsername(u.getUsername());
                return dto;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<MemberDTO> listMembers(@PathVariable String key) {
        // ADR-8 п.4: seeing members ≠ managing them — reading is a member right, not an OWNER/SA one
        requireOperate(key, AuthorizationService.Action.VIEW_MEMBERS);
        ProcessEntity process = resolveProcess(key);
        return processMemberRepository.findByProcessId(process.getId()).stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public MemberDTO addMember(@PathVariable String key, @RequestBody AddMemberDTO dto) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        ProcessEntity process = resolveProcess(key);

        ProcessRole role = requireValidRole(dto.getRole());

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
        member.setRole(role.name());
        member.setAddedBy(addedBy);
        member.setAddedAt(Instant.now());
        processMemberRepository.save(member);

        auditLogService.record(getPrincipal(), "MEMBER_ADD", key, dto.getUserId().toString());
        return toDTO(member);
    }

    @Transactional
    @Override
    public MemberDTO changeRole(@PathVariable String key, @PathVariable UUID userId, @RequestBody ChangeRoleDTO dto) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        ProcessEntity process = resolveProcess(key);

        ProcessRole role = requireValidRole(dto.getRole());

        ProcessMemberEntity member = processMemberRepository.findById(
            new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        requireOwnerRemains(process, member, role);

        member.setRole(role.name());
        processMemberRepository.save(member);
        auditLogService.record(getPrincipal(), "MEMBER_ROLE_CHANGE", key, userId.toString());
        return toDTO(member);
    }

    @Transactional
    @Override
    public IdDTO removeMember(@PathVariable String key, @PathVariable UUID userId) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        ProcessEntity process = resolveProcess(key);

        ProcessMemberEntity member = processMemberRepository.findById(
            new com.zorrodev.bpm.engine.entity.ProcessMemberId(process.getId(), userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        // Last-OWNER guard — single function shared with changeRole (WO-ACL-2 п.3)
        requireOwnerRemains(process, member, null);

        processMemberRepository.delete(member);

        // Cascade: remove API key grants for this process (WO-MT-9f)
        apiKeyRepository.findByOwnerUserId(userId).ifPresent(apiKey -> {
            apiKeyGrantRepository.findByApiKeyId(apiKey.getId()).stream()
                .filter(g -> process.getId().equals(g.getProcessId()))
                .forEach(g -> apiKeyGrantRepository.delete(g));
        });

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
