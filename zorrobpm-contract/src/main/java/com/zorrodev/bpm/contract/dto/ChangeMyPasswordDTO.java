package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-SEC-58: self-service password change. Exactly two fields — the caller can
 * change ONLY their own password through this DTO; role/active/other users are
 * unreachable by construction (separate endpoint, separate type).
 */
@Getter
@Setter
public class ChangeMyPasswordDTO {
    private String currentPassword;
    private String newPassword;
}
