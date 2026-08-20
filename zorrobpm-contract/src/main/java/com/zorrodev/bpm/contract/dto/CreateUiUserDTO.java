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
    /** WO-INT-4: "HUMAN" or "SYSTEM"; defaults to HUMAN when omitted.
     *  A SYSTEM account has no password (login is impossible) and is exempt
     *  from forcePasswordChange. */
    private String userType;
}
