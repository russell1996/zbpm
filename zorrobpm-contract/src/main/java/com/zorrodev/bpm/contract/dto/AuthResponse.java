package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.UiUser;
import lombok.Getter;
import lombok.Setter;

/** Result of a successful login: the bearer token plus the authenticated user. */
@Getter
@Setter
public class AuthResponse {
    private String token;
    private UiUser user;
}
