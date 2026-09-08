package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-REG-4: payload for POST /auth/verify-email — single raw token string.
 */
@Getter
@Setter
public class VerifyEmailDTO {
    private String token;
}
