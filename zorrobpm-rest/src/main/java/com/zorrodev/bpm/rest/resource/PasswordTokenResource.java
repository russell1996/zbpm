package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.PasswordTokenContract;
import com.zorrodev.bpm.contract.dto.ForgotPasswordDTO;
import com.zorrodev.bpm.contract.dto.ResetPasswordDTO;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public void forgotPassword(@RequestBody ForgotPasswordDTO dto) {
        // WO-ACL-18 criterion 12: enumeration-safe — always 200, identical response
        // regardless of whether the email maps to a real account.
        String clientIp = request.getRemoteAddr();
        invitationService.requestReset(dto.getEmail(), clientIp);
    }

    @Override
    public void resetPassword(@RequestBody ResetPasswordDTO dto) {
        try {
            invitationService.consumeToken(dto.getToken(), dto.getPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
        }
    }

    @Override
    public void acceptInvitation(@RequestBody ResetPasswordDTO dto) {
        try {
            invitationService.consumeToken(dto.getToken(), dto.getPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
        }
    }
}
