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

    @Override
    public void register(@Valid @RequestBody RegisterDTO dto) {
        // WO-SEC-84: IP — из атрибута RateLimitFilter (вычислен ДО
        // ForwardedHeaderFilter-переписывания remoteAddr из недоверенного XFF).
        // Вызов getClientIp() отсюда, из контроллера, уже видел бы подделанное
        // значение. Нет атрибута (путь почему-то не прошёл через фильтр) —
        // fail-closed: отказываем, а не угадываем IP по-другому.
        Object attr = request.getAttribute(RateLimitFilter.CLIENT_IP_ATTRIBUTE);
        if (!(attr instanceof String clientIp)) {
            log.warn("register without {} — RateLimitFilter did not run, rejecting fail-closed",
                RateLimitFilter.CLIENT_IP_ATTRIBUTE);
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
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
