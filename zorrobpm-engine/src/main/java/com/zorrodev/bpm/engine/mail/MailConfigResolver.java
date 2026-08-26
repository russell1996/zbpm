package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import com.zorrodev.bpm.engine.repository.MailSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * WO-INT-6 criteria 1-3: resolves the effective mail configuration.
 * A row in {@code mail_settings} overrides environment variables (zorrobpm.mail.*);
 * when no row exists the environment values are used. Resolution happens per call,
 * so a saved change applies without an application restart.
 */
@Service
@RequiredArgsConstructor
public class MailConfigResolver {

    private final MailSettingsRepository settingsRepository;
    private final MailSettingsCrypto crypto;
    private final MailProperties mailProperties;

    public ResolvedMailConfig getEffectiveConfig() {
        MailSettingsEntity row = settingsRepository.findFirstByOrderByIdAsc().orElse(null);
        if (row != null && row.getHost() != null && !row.getHost().isBlank()) {
            return new ResolvedMailConfig(
                row.getHost(),
                row.getPort(),
                row.getUsername(),
                crypto.decryptOrNull(row.getPasswordEncrypted()),
                row.getSender(),
                row.getAllowedRecipients()
            );
        }
        return new ResolvedMailConfig(
            mailProperties.host(),
            mailProperties.port(),
            mailProperties.username(),
            mailProperties.password(),
            mailProperties.from(),
            mailProperties.allowedRecipients()
        );
    }
}
