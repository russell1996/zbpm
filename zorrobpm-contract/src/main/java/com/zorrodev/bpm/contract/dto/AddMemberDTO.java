package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.ProcessRole;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
public class AddMemberDTO {
    @NotNull(message = "userId is required")
    private UUID userId;
    @NotNull(message = "role is required")
    private ProcessRole role;
}
