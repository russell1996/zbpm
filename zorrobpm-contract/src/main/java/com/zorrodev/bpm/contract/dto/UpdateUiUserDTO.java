package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUiUserDTO {
    private String fullName;
    private String email;
    private String role;
    private Boolean active;
    /** Optional: when non-blank, resets the user's password. */
    private String password;
}
