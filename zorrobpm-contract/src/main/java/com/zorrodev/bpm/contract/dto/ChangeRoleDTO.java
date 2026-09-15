package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.ProcessRole;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
public class ChangeRoleDTO {
    @NotNull(message = "role is required")
    private ProcessRole role;
}
