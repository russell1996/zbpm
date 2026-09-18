package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

@Getter
@Setter
public class ResetPasswordDTO {
    @NotBlank(message = "token is required")
    private String token;
    @NotBlank(message = "password is required")
    private String password;
}
