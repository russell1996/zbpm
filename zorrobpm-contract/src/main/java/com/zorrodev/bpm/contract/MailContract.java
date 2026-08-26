package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import com.zorrodev.bpm.contract.dto.MailSettingsDTO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * WO-INT-6: in-app mail settings management. Admin-only — enforced by {@code MailResource}.
 */
public interface MailContract {

    @GetExchange("/admin/mail/health")
    MailHealthDTO getMailHealth();

    @GetExchange("/admin/mail/settings")
    MailSettingsDTO getMailSettings();

    @PutExchange("/admin/mail/settings")
    MailSettingsDTO saveMailSettings(@RequestBody MailSettingsDTO settings);

    @PostExchange("/admin/mail/test-self")
    String testMailSettingsToSelf(@RequestBody MailSettingsDTO settings);
}
