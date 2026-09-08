package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RegistrationContract;
import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * WO-REG-3: public, unauthenticated self-registration (path whitelisted in
 * {@code JwtAuthFilter.isPublicPath}, same as the password-token endpoints).
 * Thin by design: validation, conflicts, rate limiting and mailing all live in
 * {@code SelfRegistrationService} — this class only resolves the client IP
 * (proxy-aware, like {@code PasswordTokenResource}).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RegistrationResource implements RegistrationContract {

    private final SelfRegistrationService registrationService;
    private final HttpServletRequest request;
    private final RateLimitFilter rateLimitFilter;

    @Override
    public void register(@RequestBody RegisterDTO dto) {
        // B5 (HOLD precedent from forgot-password): proxy-aware client IP so the
        // per-IP register bucket is not collapsed onto the proxy address.
        String clientIp = rateLimitFilter.getClientIp(request);
        registrationService.register(dto, clientIp);
    }
}
