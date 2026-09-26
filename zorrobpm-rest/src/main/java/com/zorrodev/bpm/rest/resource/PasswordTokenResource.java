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
    private final RateLimitFilter rateLimitFilter;

    @Override
    public void forgotPassword(@Valid @RequestBody ForgotPasswordDTO dto) {
        // WO-ACL-18 criterion 12: enumeration-safe — always 200, identical response
        // regardless of whether the email maps to a real account.
        // B5 (HOLD): use the proxy-aware client IP (honors X-Forwarded-For behind a
        // configured trusted proxy) instead of the raw socket peer, so the per-IP
        // reset bucket is not collapsed onto the proxy address for all users.
        // WO-ACL-19 (P0): any internal failure (DB error, NPE, ...) MUST NOT escape as a
        // 500 — the endpoint is enumeration-safe and must look identical to the client.
        // We log the real exception on ERROR so the next incident has a stack trace in
        // `docker logs`, then return normally (the client always sees the same 200).
        String clientIp = rateLimitFilter.getClientIp(request);
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
