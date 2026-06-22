package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateUiUserDTO {
    private String username;
    private String password;
    private String fullName;
    private String email;
    /** "ADMIN" or "USER"; defaults to USER when omitted. */
    private String role;
    private Boolean active;
}
