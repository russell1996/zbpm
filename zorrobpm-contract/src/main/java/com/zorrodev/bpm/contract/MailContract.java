package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.MailCheckResultDTO;
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

    /**
     * WO-INT-8: pre-save check — connects/authenticates against the CURRENT form values
     * (possibly unsaved), sends no email. Replaces the old test-self-with-arbitrary-values flow.
     */
    @PostExchange("/admin/mail/check")
    MailCheckResultDTO checkMailSettings(@RequestBody MailSettingsDTO settings);

    /**
     * WO-INT-8: post-save real send — uses the SAVED config only ({@code MailConfigResolver}),
     * no password (or any other value) travels from the frontend. Available only once a config
     * is actually saved.
     */
    @PostExchange("/admin/mail/test-self")
    void testMailSettingsToSelf();
}
