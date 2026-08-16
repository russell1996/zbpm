package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.ProcessRole;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class AddMemberDTO {
    private UUID userId;
    private ProcessRole role;
}
