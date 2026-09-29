package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginDTO {
    /** WO-AUTH-1: username ИЛИ email пользователя (поле не переименовано — контракт). */
    @NotBlank(message = "username is required")
    private String username;
    @NotBlank(message = "password is required")
    private String password;
}
