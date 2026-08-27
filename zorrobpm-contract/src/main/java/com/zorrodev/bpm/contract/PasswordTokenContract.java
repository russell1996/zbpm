package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.ForgotPasswordDTO;
import com.zorrodev.bpm.contract.dto.ResetPasswordDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** WO-ACL-18: public, unauthenticated password-token endpoints (invitation / reset). */
public interface PasswordTokenContract {

    @PostExchange("/auth/forgot-password")
    void forgotPassword(@RequestBody ForgotPasswordDTO dto);

    @PostExchange("/auth/reset-password")
    void resetPassword(@RequestBody ResetPasswordDTO dto);

    @PostExchange("/auth/accept-invitation")
    void acceptInvitation(@RequestBody ResetPasswordDTO dto);
}
