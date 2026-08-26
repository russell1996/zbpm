package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import com.zorrodev.bpm.engine.repository.MailSettingsRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-6 criteria 1, 2, 5 (at rest): the migration creates the real {@code mail_settings} table
 * on PostgreSQL and a stored password is kept as ciphertext, never plaintext. Runs against a real
 * PostgreSQL instance (see PostgresIT). Criterion 5's at-rest encryption is proven by reading the raw
 * column via JDBC and confirming it differs from the plaintext.
 */
@Tag("pg")
public class MailSettingsPgIT extends PostgresIT {

    private static final String KEY_HEX =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired MailSettingsRepository settingsRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void migrationApplied_tableExists_andPasswordStoredEncrypted() {
        MailSettingsCrypto crypto = new MailSettingsCrypto(KEY_HEX);

        MailSettingsEntity e = new MailSettingsEntity();
        e.setHost("pg.host");
        e.setPort(587);
        e.setUsername("pguser");
        e.setPasswordEncrypted(crypto.encrypt("pg-secret"));
        e.setSender("pg@corp.kz");
        e.setAllowedRecipients("ops@corp.kz");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        settingsRepository.save(e);

        // Reload through the JPA repository — proves the table + mapping work on real PG.
        Optional<MailSettingsEntity> reloaded = settingsRepository.findFirstByOrderByIdAsc();
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getHost()).isEqualTo("pg.host");
        assertThat(crypto.decrypt(reloaded.get().getPasswordEncrypted())).isEqualTo("pg-secret");

        // Raw column must contain ciphertext, not the plaintext password.
        String raw = jdbcTemplate.queryForObject(
            "select password_encrypted from mail_settings where id = ?",
            String.class, MailSettingsEntity.SINGLE_ROW_ID);
        assertThat(raw).isNotNull();
        assertThat(raw).isNotEqualTo("pg-secret");
        assertThat(raw).doesNotContain("pg-secret");
    }
}
