package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * WO-REG-4: payload for POST /auth/verify-email — single raw token string.
 */
@Getter
@Setter
public class VerifyEmailDTO {
    @NotBlank(message = "token is required")
    private String token;
}
