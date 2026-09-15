package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RegistrationContract;
import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.dto.VerifyEmailDTO;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import jakarta.validation.Valid;

/**
 * WO-REG-3/4: public, unauthenticated self-registration + email verification
 * (both paths whitelisted in {@code JwtAuthFilter.isPublicPath}).
 * Thin by design: validation, conflicts, rate limiting and mailing all live in
 * {@code SelfRegistrationService} — this class only resolves the client IP
 * (proxy-aware, like {@code PasswordTokenResource}) for register.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RegistrationResource implements RegistrationContract {

    private final SelfRegistrationService registrationService;
    private final HttpServletRequest request;
    private final RateLimitFilter rateLimitFilter;

    @Override
    public void register(@Valid @RequestBody RegisterDTO dto) {
        // B5 (HOLD precedent from forgot-password): proxy-aware client IP so the
        // per-IP register bucket is not collapsed onto the proxy address.
        String clientIp = rateLimitFilter.getClientIp(request);
        registrationService.register(dto, clientIp);
    }

    @Override
    public void verifyEmail(@Valid @RequestBody VerifyEmailDTO dto) {
        String token = dto == null ? null : dto.getToken();
        try {
            registrationService.verifyEmail(token);
        } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (NoSuchElementException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid or expired token", e);
        }
    }
}
