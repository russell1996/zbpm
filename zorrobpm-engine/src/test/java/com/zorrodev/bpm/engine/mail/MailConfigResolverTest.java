package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import com.zorrodev.bpm.engine.repository.MailSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailConfigResolverTest {

    private static final String KEY_HEX =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void criterion1_dbRowOverridesEnv() {
        MailSettingsRepository repo = mock(MailSettingsRepository.class);
        MailSettingsEntity row = new MailSettingsEntity();
        row.setHost("db.host");
        row.setPort(2525);
        row.setUsername("dbuser");
        row.setPasswordEncrypted(new MailSettingsCrypto(KEY_HEX).encrypt("dbpass"));
        row.setSender("db@corp.kz");
        row.setAllowedRecipients("a@corp.kz");
        when(repo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(row));

        MailProperties env = new MailProperties("env.host", 587, "envuser", "envpass", "env@corp.kz", "");
        MailConfigResolver resolver = new MailConfigResolver(repo, new MailSettingsCrypto(KEY_HEX), env);

        ResolvedMailConfig cfg = resolver.getEffectiveConfig();
        assertThat(cfg.host()).isEqualTo("db.host");
        assertThat(cfg.port()).isEqualTo(2525);
        assertThat(cfg.username()).isEqualTo("dbuser");
        assertThat(cfg.password()).isEqualTo("dbpass");
        assertThat(cfg.from()).isEqualTo("db@corp.kz");
        assertThat(cfg.allowedRecipients()).isEqualTo("a@corp.kz");
    }

    @Test
    void criterion2_envUsed_whenNoRow() {
        MailSettingsRepository repo = mock(MailSettingsRepository.class);
        when(repo.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        MailProperties env = new MailProperties("env.host", 587, "envuser", "envpass", "env@corp.kz", "x@corp.kz");
        MailConfigResolver resolver = new MailConfigResolver(repo, new MailSettingsCrypto(KEY_HEX), env);

        ResolvedMailConfig cfg = resolver.getEffectiveConfig();
        assertThat(cfg.host()).isEqualTo("env.host");
        assertThat(cfg.password()).isEqualTo("envpass");
        assertThat(cfg.allowedRecipients()).isEqualTo("x@corp.kz");
    }

    @Test
    void criterion3_changeAppliesWithoutRestart() {
        MailSettingsRepository repo = mock(MailSettingsRepository.class);
        when(repo.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        MailProperties env = new MailProperties("env.host", 587, "envuser", "envpass", "env@corp.kz", "");
        MailSettingsCrypto crypto = new MailSettingsCrypto(KEY_HEX);
        MailConfigResolver resolver = new MailConfigResolver(repo, crypto, env);

        // Initially env.
        assertThat(resolver.getEffectiveConfig().host()).isEqualTo("env.host");

        // A row appears (simulating a save) — same resolver instance, no restart.
        MailSettingsEntity row = new MailSettingsEntity();
        row.setHost("new.host");
        row.setPort(9999);
        row.setUsername("u");
        row.setPasswordEncrypted(crypto.encrypt("p"));
        row.setSender("s@corp.kz");
        row.setAllowedRecipients("");
        when(repo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(row));

        ResolvedMailConfig cfg = resolver.getEffectiveConfig();
        assertThat(cfg.host()).isEqualTo("new.host");
        assertThat(cfg.port()).isEqualTo(9999);
    }
}
