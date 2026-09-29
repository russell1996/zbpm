package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import com.zorrodev.bpm.engine.service.RegistrationCleanupJob;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REG-7 criteria 2+6 on real PostgreSQL: a stale PENDING_EMAIL_VERIFICATION
 * registration is deleted with its token rows, the address becomes registrable
 * again, and a fresh registration in the same run is untouched. H2 cannot be
 * trusted for schema-adjacent behavior (column mapping, delete semantics), so
 * the end-to-end proof lives here; the full matrix lives in
 * {@code RegistrationCleanupJobTest} (H2).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class RegistrationCleanupPgIT extends PostgresIT {

    @Autowired private RegistrationCleanupJob cleanupJob;
    @Autowired private SelfRegistrationService registrationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private StubMailSender mailSender;
    @Autowired @Qualifier("registrationRateLimiter") private PasswordResetRateLimiter registerLimiter;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mailSender.clear();
        registerLimiter.reset();
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone (deleted by the job under test) — best effort
            }
        }
        cleanupIds.clear();
    }

    private RegisterDTO dto(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("Cleanup PG Probe");
        dto.setEmail(email);
        return dto;
    }

    @Test
    void stalePending_deletedTokensGoneAddressFreed_freshUntouched() {
        String staleEmail = "pg-stale-" + UUID.randomUUID().toString().substring(0, 8) + "@x.y";
        registrationService.register(dto("pgstale", staleEmail), "10.8.0.1");
        UiUserEntity stale = userRepository.findAll().stream()
            .filter(u -> staleEmail.equals(u.getEmail())).findFirst().orElseThrow();
        stale.setCreatedAt(Instant.now().minusSeconds(25L * 3600L));
        userRepository.save(stale);
        cleanupIds.add(stale.getId());
        assertThat(tokenRepository.findAll().stream()
            .filter(t -> stale.getId().equals(t.getUserId())).count()).isGreaterThan(0);

        String freshEmail = "pg-fresh-" + UUID.randomUUID().toString().substring(0, 8) + "@x.y";
        registrationService.register(dto("pgfresh", freshEmail), "10.8.0.2");
        UiUserEntity fresh = userRepository.findAll().stream()
            .filter(u -> freshEmail.equals(u.getEmail())).findFirst().orElseThrow();
        cleanupIds.add(fresh.getId());

        assertThat(cleanupJob.cleanExpired()).isEqualTo(1);
        assertThat(userRepository.findById(stale.getId())).isEmpty();
        assertThat(tokenRepository.findAll().stream()
            .filter(t -> stale.getId().equals(t.getUserId()))).isEmpty();
        assertThat(userRepository.findById(fresh.getId())).isPresent();

        registrationService.register(dto("pgstale-again", staleEmail), "10.8.0.3");
        List<UiUserEntity> reborn = userRepository.findAll().stream()
            .filter(u -> staleEmail.equals(u.getEmail())).toList();
        assertThat(reborn).hasSize(1);
        assertThat(reborn.get(0).getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        cleanupIds.add(reborn.get(0).getId());
    }
}
