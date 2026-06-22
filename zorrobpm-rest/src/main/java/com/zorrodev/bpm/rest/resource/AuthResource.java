package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.AuthContract;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AuthResource implements AuthContract {

    private final UiUserService userService;
    private final HttpServletRequest request;

    @Override
    public AuthResponse login(@RequestBody LoginDTO dto) {
        return userService.login(dto)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
    }

    @Override
    public UiUser me() {
        // the auth filter has already validated the token and stashed the claims
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        return userService.getById(claims.userId());
    }
}
