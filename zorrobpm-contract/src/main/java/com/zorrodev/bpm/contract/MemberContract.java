package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberCandidateDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

public interface MemberContract {

    @GetExchange("/admin/users/{userId}/memberships")
    List<MemberDTO> listUserMemberships(@PathVariable UUID userId);

    /** WO-ACL-7: the current user's own memberships — self-service, no admin endpoint needed. */
    @GetExchange("/me/memberships")
    List<MemberDTO> listMyMemberships();

    @GetExchange("/processes/{key}/members")
    List<MemberDTO> listMembers(@PathVariable String key);

    /**
     * WO-ACL-7: user candidates to add to a process — MANAGE_MEMBERS on the process.
     * WO-ACL-15 part B: {@code q} is OPTIONAL — an empty query returns the first page
     * (capped, sorted by name then login); the cap and MANAGE_MEMBERS are what keep
     * this from being a user directory (/users stays SUPER_ADMIN-only).
     * WO-ACL-15: the DTO also carries fullName and email (empty strings when absent) —
     * the same two fields MemberDTO already exposes to every authenticated user.
     */
    @GetExchange("/processes/{key}/members/candidates")
    List<MemberCandidateDTO> candidateMembers(@PathVariable String key, @RequestParam String q);

    @PostExchange("/processes/{key}/members")
    MemberDTO addMember(@PathVariable String key, @RequestBody AddMemberDTO dto);

    @PatchExchange("/processes/{key}/members/{userId}")
    MemberDTO changeRole(@PathVariable String key, @PathVariable UUID userId, @RequestBody ChangeRoleDTO dto);

    @DeleteExchange("/processes/{key}/members/{userId}")
    IdDTO removeMember(@PathVariable String key, @PathVariable UUID userId);
}
