package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.dto.VerifyEmailDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * WO-REG-3/4: public, unauthenticated self-registration + email verification.
 * Separate contract from {@code AuthContract}, same as {@code PasswordTokenContract}.
 */
public interface RegistrationContract {

    @PostExchange("/auth/register")
    void register(@RequestBody RegisterDTO dto);

    @PostExchange("/auth/verify-email")
    void verifyEmail(@RequestBody VerifyEmailDTO dto);
}
