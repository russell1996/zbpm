package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * WO-REG-3: public, unauthenticated self-registration endpoint. Separate contract
 * from {@code AuthContract}, same as {@code PasswordTokenContract} keeps the public
 * password-token endpoints apart.
 */
public interface RegistrationContract {

    @PostExchange("/auth/register")
    void register(@RequestBody RegisterDTO dto);
}
