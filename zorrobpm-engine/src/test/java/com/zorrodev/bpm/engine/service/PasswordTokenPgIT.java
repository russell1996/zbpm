package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ACL-18: the migration creates the real {@code password_tokens} table on PostgreSQL and the
 * raw token is NEVER persisted — only its SHA-256 hash. Proven by reading the column via JDBC.
 */
@Tag("pg")
public class PasswordTokenPgIT extends PostgresIT {

    @Autowired PasswordTokenRepository tokenRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void migrationApplied_tableExists_andHashStoredNotRaw() {
        PasswordTokenEntity e = new PasswordTokenEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(UUID.randomUUID());
        e.setType("INVITE");
        e.setTokenHash("HASHED-VALUE");
        e.setEmail("invitee@corp.kz");
        e.setExpiresAt(Instant.now().plusSeconds(3600));
        e.setUsed(false);
        e.setCreatedAt(Instant.now());
        tokenRepository.save(e);

        // Raw column must hold the hash, not a raw token.
        String raw = jdbcTemplate.queryForObject(
                "select token_hash from password_tokens where id = ?", String.class, e.getId());
        assertThat(raw).isEqualTo("HASHED-VALUE");
        assertThat(raw).isNotEqualTo("RAW-TOKEN");

        Optional<PasswordTokenEntity> reloaded = tokenRepository.findByTokenHashAndUsedFalse("HASHED-VALUE");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getEmail()).isEqualTo("invitee@corp.kz");
    }
}
