package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.ChangeMyPasswordDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PutExchange;

/**
 * WO-SEC-58: "My profile" self-service endpoints. Available to ANY authenticated
 * user; operates strictly on the CALLER's own account.
 */
public interface MyProfileContract {

    /**
     * Change the caller's own password. Requires the current password (a stolen
     * session must not become an account takeover); on success clears
     * forcePasswordChange. Rate-limited like login.
     */
    @PutExchange("/me/password")
    IdDTO changeMyPassword(@RequestBody ChangeMyPasswordDTO dto);
}
