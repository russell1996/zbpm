package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-REG-3: self-registration payload — username, password, fullName, email ONLY.
 * Deliberately NO {@code role} field: the server always creates {@code role="USER"},
 * so privilege escalation through this path is structurally impossible, not just
 * validated away. Unknown JSON properties (e.g. a smuggled {@code "role"}) are
 * ignored by Jackson's default lenient binding.
 */
@Getter
@Setter
public class RegisterDTO {
    /** WO-API-1: пустые credentials давали 422 из сервиса вместо 400 с DTO. */
    @NotBlank(message = "username is required")
    private String username;
    @NotBlank(message = "password is required")
    private String password;
    @NotBlank(message = "fullName is required")
    private String fullName;
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;
}
