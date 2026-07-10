package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

public interface AuthContract {

    /** Authenticates a user and returns a bearer token. Public (no token required). */
    @PostExchange("/auth/login")
    AuthResponse login(@RequestBody LoginDTO dto);

    /** Returns the currently authenticated user (requires a valid bearer token). */
    @GetExchange("/auth/me")
    UiUser me();

    /** Refreshes access token using a valid refresh token cookie. Public (refresh token in cookie). */
    @PostExchange("/auth/refresh")
    AuthResponse refresh();

    /** Revokes refresh token and clears cookie. Requires valid bearer token. */
    @PostExchange("/auth/logout")
    void logout();
}
