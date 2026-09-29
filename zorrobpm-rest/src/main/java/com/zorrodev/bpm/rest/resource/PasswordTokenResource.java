package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.PasswordTokenContract;
import com.zorrodev.bpm.contract.dto.ForgotPasswordDTO;
import com.zorrodev.bpm.contract.dto.ResetPasswordDTO;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

/**
 * WO-ACL-18: public, unauthenticated endpoints for the one-time password links.
 * These paths are whitelisted in {@code JwtAuthFilter.isPublicPath}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PasswordTokenResource implements PasswordTokenContract {

    private final UserInvitationService invitationService;
    private final HttpServletRequest request;

    @Override
    public void forgotPassword(@Valid @RequestBody ForgotPasswordDTO dto) {
        // WO-SEC-84: IP — из атрибута RateLimitFilter (вычислен ДО
        // ForwardedHeaderFilter-переписывания; вызов getClientIp() отсюда уже
        // видел бы подделанный XFF). Fail-closed при отсутствии атрибута —
        // ведём себя как при исчерпанном лимите: та же тихая enumeration-safe
        // 200, плюс ERROR-лог для диагностики.
        Object attr = request.getAttribute(RateLimitFilter.CLIENT_IP_ATTRIBUTE);
        if (!(attr instanceof String clientIp)) {
            log.error("forgotPassword without {} — RateLimitFilter did not run, treating as rate-limited",
                RateLimitFilter.CLIENT_IP_ATTRIBUTE);
            return;
        }
        try {
            invitationService.requestReset(dto.getEmail(), clientIp);
        } catch (Exception e) {
            log.error("forgotPassword failed for email={}", dto.getEmail(), e);
        }
    }

    @Override
    public void resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        try {
            invitationService.consumeToken(dto.getToken(), dto.getPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
        }
    }

    @Override
    public void acceptInvitation(@Valid @RequestBody ResetPasswordDTO dto) {
        try {
            invitationService.consumeToken(dto.getToken(), dto.getPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
        }
    }
}
