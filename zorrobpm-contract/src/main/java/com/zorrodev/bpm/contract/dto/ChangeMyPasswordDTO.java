package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * WO-SEC-58: self-service password change. Exactly two fields — the caller can
 * change ONLY their own password through this DTO; role/active/other users are
 * unreachable by construction (separate endpoint, separate type).
 */
@Getter
@Setter
public class ChangeMyPasswordDTO {
    @NotBlank(message = "currentPassword is required")
    private String currentPassword;
    @NotBlank(message = "newPassword is required")
    private String newPassword;
}
