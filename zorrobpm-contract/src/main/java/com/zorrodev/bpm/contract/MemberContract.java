package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AddMemberDTO;
import com.zorrodev.bpm.contract.dto.ChangeRoleDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MemberDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

public interface MemberContract {

    @GetExchange("/admin/users/{userId}/memberships")
    List<MemberDTO> listUserMemberships(@PathVariable UUID userId);

    @GetExchange("/processes/{key}/members")
    List<MemberDTO> listMembers(@PathVariable String key);

    @PostExchange("/processes/{key}/members")
    MemberDTO addMember(@PathVariable String key, @RequestBody AddMemberDTO dto);

    @PatchExchange("/processes/{key}/members/{userId}")
    MemberDTO changeRole(@PathVariable String key, @PathVariable UUID userId, @RequestBody ChangeRoleDTO dto);

    @DeleteExchange("/processes/{key}/members/{userId}")
    IdDTO removeMember(@PathVariable String key, @PathVariable UUID userId);
}
