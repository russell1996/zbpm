package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;

@Getter
@Setter
public class ForgotPasswordDTO {
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;
}
