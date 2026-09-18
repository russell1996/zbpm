package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MemberContract;
import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberCandidateDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ProcessMemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

/**
 * WO-DEBT-7 S4 — thin facade over {@link ProcessMemberService}: auth checks +
 * delegation. All persistence access (reads and every {@code .save()} /
 * {@code .delete()}) lives in the service, inside this class' transaction (no
 * {@code @Transactional} on the service — same as the original layout, proven
 * by {@code MemberTransactionalIT}: audit failure rolls the mutation back).
 * Zero direct persistence imports.
 */
@RestController
@RequiredArgsConstructor
public class MemberResource implements MemberContract {

    private final ProcessMemberService processMemberService;
    private final AuthorizationService authorizationService;
    private final HttpServletRequest request;
    /** WO-SEC-67 (F13): close/narrow live SSE streams on membership change. */
    private final SseEventStreamService sseEventStreamService;

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

    @Override
    public List<MemberDTO> listUserMemberships(UUID userId) {
        requireSuperAdmin();
        return processMemberService.listMembershipsForUser(userId);
    }

    /**
     * WO-ACL-7 criterion 1: the caller's OWN memberships only — the userId comes from the
     * authenticated principal, never from a path/query parameter. "My memberships" is
     * self-data: no cross-user access, no admin directory leak.
     */
    @Override
    public List<MemberDTO> listMyMemberships() {
        Principal.UserPrincipal user = requireUserPrincipal();
        return processMemberService.listMembershipsForUser(user.userId());
    }

    @Override
    public List<MemberCandidateDTO> candidateMembers(@PathVariable String key, String q) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        return processMemberService.findCandidates(key, q);
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
        return processMemberService.listMembers(key);
    }

    @Transactional
    @Override
    public MemberDTO addMember(@PathVariable String key, @Valid @RequestBody AddMemberDTO dto) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        return processMemberService.addMember(key, dto, getPrincipal());
    }

    @Transactional
    @Override
    public MemberDTO changeRole(@PathVariable String key, @PathVariable UUID userId, @Valid @RequestBody ChangeRoleDTO dto) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        MemberDTO result = processMemberService.changeRole(key, userId, dto, getPrincipal());
        // WO-SEC-67 (F13): rights may have narrowed — re-check open streams now.
        sseEventStreamService.invalidateStreams();
        return result;
    }

    @Transactional
    @Override
    public IdDTO removeMember(@PathVariable String key, @PathVariable UUID userId) {
        requireOperate(key, AuthorizationService.Action.MANAGE_MEMBERS);
        IdDTO result = processMemberService.removeMember(key, userId, getPrincipal());
        // WO-SEC-67 (F13): membership gone — close that user's narrowed streams now.
        sseEventStreamService.invalidateStreams();
        return result;
    }
}
