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
     * WO-ACL-7: hard cap on the candidate result set — never the whole user table.
     * WO-ACL-15 (part B): the cap is what keeps the endpoint from becoming a user
     * directory when {@code q} is empty — the query length guard was removed.
     */
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
        // WO-INT-4 criterion 4: SYSTEM accounts are NOT counted as owners — a process whose
        // only owner is a robot is organizationally ownerless. Only HUMAN owners keep the
        // "at least one owner" invariant.
        List<ProcessMemberEntity> owners = processMemberRepository.findByProcessId(process.getId()).stream()
            .filter(m -> ProcessRole.fromName(m.getRole()) == ProcessRole.OWNER)
            .toList();
        long humanOwnerCount = owners.stream()
            .filter(m -> !isSystemAccount(m.getUserId()))
            .count();
        if (humanOwnerCount <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove or demote the last OWNER");
        }
    }

    private boolean isSystemAccount(UUID userId) {
        return uiUserRepository.findById(userId)
            .map(u -> "SYSTEM".equals(u.getUserType()))
            .orElse(false);
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
     * search: MANAGE_MEMBERS on the process, active users only, members excluded,
     * result capped at MAX_CANDIDATES. WO-ACL-15 part B: an empty {@code q} is now
     * allowed — it returns the FIRST page (the cap + MANAGE_MEMBERS are what keep
     * this from being a user directory) — sorted by name, then login, so the list
     * is stable between openings. Output carries fullName + email (both already
     * public via MemberDTO), as empty strings when the account has none.
     */
    @Override
    public List<MemberCandidateDTO> candidateMembers(@PathVariable String key, String q) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        ProcessEntity process = resolveProcess(key);

        String query = q == null ? "" : q.trim();

        Set<UUID> memberIds = processMemberRepository.findByProcessId(process.getId()).stream()
            .map(ProcessMemberEntity::getUserId)
            .collect(Collectors.toSet());

        List<Specification<UiUserEntity>> specs = new ArrayList<>();
        specs.add(UiUserRepository.byUsernameContains(query));
        specs.add(UiUserRepository.byActive(true));
        // WO-INT-4 criterion 5: system accounts are never offered as candidates — a human
        // task assigned to a system would never be executed and would appear in nobody's inbox.
        specs.add((root, cbq, cb) -> cb.or(
            cb.isNull(root.get("userType")),
            cb.notEqual(root.get("userType"), "SYSTEM")));
        if (!memberIds.isEmpty()) {
            specs.add((root, cbq, cb) -> cb.not(root.get("id").in(memberIds)));
        }

        // WO-ACL-15 part B: stable order — by name first, then login. Empty/absent
        // names (null in the DB) sort last on both H2 (PostgreSQL mode) and PG.
        Sort sort = Sort.by("fullName").ascending().and(Sort.by("username").ascending());

        return uiUserRepository.findAll(Specification.allOf(specs),
                PageRequest.of(0, MAX_CANDIDATES, sort))
            .getContent().stream()
            .map(u -> {
                MemberCandidateDTO dto = new MemberCandidateDTO();
                dto.setUserId(u.getId());
                dto.setUsername(u.getUsername());
                // WO-ACL-15 criterion 1: empty string, never null — the dialog renders
                // "no name/email" as absence, not as the literal "null".
                dto.setFullName(u.getFullName() == null ? "" : u.getFullName());
                dto.setEmail(u.getEmail() == null ? "" : u.getEmail());
                return dto;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<MemberDTO> listMembers(@PathVariable String key) {
        // WO-ACL-9: member list visible to ANY authenticated user (ADR-8 п.4 revised).
        // This is reading, not managing — any account should see who to contact for access.
        // The principal must exist (authentication required), but no process membership needed.
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
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

        // Resolve username, fullName, email from the same lookup (WO-ACL-7 пункт 4)
        uiUserRepository.findById(entity.getUserId()).ifPresent(u -> {
            dto.setUsername(u.getUsername());
            dto.setFullName(u.getFullName());
            dto.setEmail(u.getEmail());
            // WO-INT-4 criterion 6: flag system accounts so the member list shows
            // who is a person and who is an integration at a glance.
            dto.setIsSystem("SYSTEM".equals(u.getUserType()));
        });

        return dto;
    }
}
