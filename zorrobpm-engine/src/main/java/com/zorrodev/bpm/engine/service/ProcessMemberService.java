package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberCandidateDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WO-DEBT-7 S4: JPA-backed process-membership management, moved verbatim out of
 * the REST-layer {@code MemberResource} (which stays behind as a thin facade:
 * auth checks + delegation, {@code @Transactional} boundaries kept exactly
 * where they were). The web layer must not import persistence types; every
 * read and every {@code .save()}/{@code .delete()} lives here, inside the
 * caller's transaction (no {@code @Transactional} of its own — same as the
 * original location, proven by {@code MemberTransactionalIT}: audit failure
 * rolls the mutation back).
 *
 * <p>Responsibility: the process-membership aggregate (member lifecycle with
 * the last-OWNER invariant, candidate search, member listing with user
 * enrichment, API-key-grant cascade on removal). Deliberately NOT inside
 * {@code ApiKeyService} — that one owns the key lifecycle, this one the
 * membership lifecycle; merging them would be god-class drift, the exact
 * thing this epic series avoids (one narrow service per slice).
 */
@Component
@RequiredArgsConstructor
public class ProcessMemberService {

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
    private final AuditLogService auditLogService;

    /**
     * Memberships of one user, enriched with the process key.
     * Serves both the SUPER_ADMIN path (arbitrary userId) and the self-service
     * path (userId taken from the principal) — same query, same mapping.
     */
    public List<MemberDTO> listMembershipsForUser(UUID userId) {
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
     * WO-ACL-7 (ADR-8 п.7): who can be ADDED to this process. OWNER-scoped candidate
     * search: MANAGE_MEMBERS on the process (checked by the caller), active users only,
     * members excluded, result capped at MAX_CANDIDATES. WO-ACL-15 part B: an empty
     * {@code q} is now allowed — it returns the FIRST page (the cap + MANAGE_MEMBERS
     * are what keep this from being a user directory) — sorted by name, then login,
     * so the list is stable between openings. Output carries fullName + email (both
     * already public via MemberDTO), as empty strings when the account has none.
     */
    public List<MemberCandidateDTO> findCandidates(String processKey, String q) {
        ProcessEntity process = resolveProcess(processKey);

        String query = q == null ? "" : q.trim();

        Set<UUID> memberIds = processMemberRepository.findByProcessId(process.getId()).stream()
            .map(ProcessMemberEntity::getUserId)
            .collect(Collectors.toSet());

        List<Specification<UiUserEntity>> specs = new ArrayList<>();
        // WO-ACL-17: match login, full name or email — the fields the dialog displays
        specs.add(UiUserRepository.byCandidateSearchContains(query));
        specs.add(UiUserRepository.byActive(true));
        // WO-INT-4 criterion 3: system accounts are never offered as candidates — a human
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

    public List<MemberDTO> listMembers(String processKey) {
        ProcessEntity process = resolveProcess(processKey);
        return processMemberRepository.findByProcessId(process.getId()).stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public MemberDTO addMember(String processKey, AddMemberDTO dto, Principal principal) {
        ProcessEntity process = resolveProcess(processKey);

        ProcessRole role = requireValidRole(dto.getRole());

        // Validate user exists
        UiUserEntity user = uiUserRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Check not already a member
        var existing = processMemberRepository.findById(
            new ProcessMemberId(process.getId(), dto.getUserId()));
        if (existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member");
        }

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

        auditLogService.record(principal, "MEMBER_ADD", processKey, dto.getUserId().toString());
        return toDTO(member);
    }

    public MemberDTO changeRole(String processKey, UUID userId, ChangeRoleDTO dto, Principal principal) {
        ProcessEntity process = resolveProcess(processKey);

        ProcessRole role = requireValidRole(dto.getRole());

        ProcessMemberEntity member = processMemberRepository.findById(
            new ProcessMemberId(process.getId(), userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        requireOwnerRemains(process, member, role);

        member.setRole(role.name());
        processMemberRepository.save(member);
        auditLogService.record(principal, "MEMBER_ROLE_CHANGE", processKey, userId.toString());
        return toDTO(member);
    }

    public IdDTO removeMember(String processKey, UUID userId, Principal principal) {
        ProcessEntity process = resolveProcess(processKey);

        ProcessMemberEntity member = processMemberRepository.findById(
            new ProcessMemberId(process.getId(), userId))
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

        auditLogService.record(principal, "MEMBER_REMOVE", processKey, userId.toString());

        IdDTO result = new IdDTO();
        result.setId(userId);
        return result;
    }

    private ProcessEntity resolveProcess(String key) {
        return processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found"));
    }

    /**
     * WO-ACL-2: the invariant "a process always has at least one OWNER" is enforced in ONE place
     * for both removeMember and changeRole. Previously only removeMember had it — demoting the
     * single OWNER through changeRole left the process ownerless.
     *
     * WO-INT-4: a system account is an ordinary account — the type is a marker, not a special
     * right. It counts as an OWNER like anyone else: no type-based exception in the invariant.
     */
    private void requireOwnerRemains(ProcessEntity process, ProcessMemberEntity member, ProcessRole targetRole) {
        if (ProcessRole.fromName(member.getRole()) != ProcessRole.OWNER) return;
        if (targetRole == ProcessRole.OWNER) return; // OWNER → OWNER keeps the invariant
        List<ProcessMemberEntity> owners = processMemberRepository.findByProcessId(process.getId()).stream()
            .filter(m -> ProcessRole.fromName(m.getRole()) == ProcessRole.OWNER)
            .toList();
        if (owners.size() <= 1) {
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
            // WO-INT-4 criterion 2: flag system accounts so the member list shows
            // who is a person and who is an integration at a glance.
            dto.setIsSystem("SYSTEM".equals(u.getUserType()));
        });

        return dto;
    }
}
