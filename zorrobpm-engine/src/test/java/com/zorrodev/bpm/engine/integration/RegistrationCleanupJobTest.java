package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import com.zorrodev.bpm.engine.service.RegistrationCleanupJob;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REG-7 (H2 level): TTL cleanup of stuck PENDING_EMAIL_VERIFICATION
 * registrations. Fresh/stale are separated by backdated {@code createdAt}
 * (deterministic, no sleeps); the end-to-end test registers for real,
 * backdates, runs the job, and registers the same address again.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class RegistrationCleanupJobTest {

    @Autowired private RegistrationCleanupJob cleanupJob;
    @Autowired private SelfRegistrationService registrationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private StubMailSender mailSender;
    @Autowired @Qualifier("registrationRateLimiter") private PasswordResetRateLimiter registerLimiter;

    private final List<UUID> cleanupIds = new ArrayList<>();
    private final List<UUID> cleanupKeyIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mailSender.clear();
        registerLimiter.reset();
        for (UUID id : List.copyOf(cleanupKeyIds)) {
            try {
                apiKeyRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone (deleted by the job under test) — best effort
            }
        }
        cleanupIds.clear();
        cleanupKeyIds.clear();
    }

    private RegisterDTO dto(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("Cleanup Probe");
        dto.setEmail(email);
        return dto;
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        List<UiUserEntity> found = userRepository.findAll().stream()
            .filter(u -> email.equals(u.getEmail()))
            .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /** Registers for real, then backdates the row past the TTL. Returns the user id. */
    private UUID registeredStale(String tag, String email, int ageHours) {
        registrationService.register(dto(tag, email), "10.7.0.1");
        UiUserEntity user = onlyUserWithEmail(email.toLowerCase());
        user.setCreatedAt(Instant.now().minusSeconds((long) ageHours * 3600L));
        userRepository.save(user);
        cleanupIds.add(user.getId());
        return user.getId();
    }

    private long tokenCount(UUID userId) {
        return tokenRepository.findAll().stream().filter(t -> userId.equals(t.getUserId())).count();
    }

    @Test
    void criterion1_freshPendingVerification_untouched() {
        UUID id = registeredStale("fresh", "fresh-clean@x.y", 1);
        assertThat(tokenCount(id)).isGreaterThan(0);

        assertThat(cleanupJob.cleanExpired()).isEqualTo(0);
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(tokenCount(id)).isGreaterThan(0);
    }

    @Test
    void criterion2_stalePendingVerification_deletedAndAddressFreed() {
        String email = "stale-clean@x.y";
        UUID id = registeredStale("stale", email, 25);
        assertThat(tokenCount(id)).isGreaterThan(0);

        assertThat(cleanupJob.cleanExpired()).isEqualTo(1);
        assertThat(userRepository.findById(id)).isEmpty();
        assertThat(tokenCount(id)).isEqualTo(0);

        // The address is really free: registering again succeeds (criterion 2 end-to-end).
        registrationService.register(dto("stale-again", email), "10.7.0.2");
        UiUserEntity reborn = onlyUserWithEmail(email);
        cleanupIds.add(reborn.getId());
        assertThat(reborn.getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(reborn.getId()).isNotEqualTo(id);
    }

    @Test
    void criterion3_otherStatuses_untouchedRegardlessOfAge() {
        UUID approval = registeredStale("appr", "appr-clean@x.y", 100);
        UUID active = registeredStale("act", "act-clean@x.y", 100);
        UUID rejected = registeredStale("rej", "rej-clean@x.y", 100);
        userRepository.findById(approval).ifPresent(u -> {
            u.setRegistrationStatus("PENDING_APPROVAL"); userRepository.save(u);
        });
        userRepository.findById(active).ifPresent(u -> {
            u.setRegistrationStatus("ACTIVE"); userRepository.save(u);
        });
        userRepository.findById(rejected).ifPresent(u -> {
            u.setRegistrationStatus("REJECTED"); userRepository.save(u);
        });

        assertThat(cleanupJob.cleanExpired()).isEqualTo(0);
        assertThat(userRepository.findById(approval)).isPresent();
        assertThat(userRepository.findById(active)).isPresent();
        assertThat(userRepository.findById(rejected)).isPresent();
    }

    @Test
    void criterion4_ttlIsConfigurable_notHardcoded() {
        UUID id = registeredStale("cfg", "cfg-clean@x.y", 25);
        // Widen the TTL far past the row's age: the same row must survive,
        // proving the cutoff comes from the @Value field, not a constant.
        ReflectionTestUtils.setField(cleanupJob, "verifyTtlHours", 100000);
        try {
            assertThat(cleanupJob.cleanExpired()).isEqualTo(0);
            assertThat(userRepository.findById(id)).isPresent();
        } finally {
            ReflectionTestUtils.setField(cleanupJob, "verifyTtlHours", 24);
        }
    }

    @Test
    void staleUserWithoutTokens_deletedAnyway() {
        UiUserEntity orphan = new UiUserEntity();
        orphan.setId(UUID.randomUUID());
        orphan.setUsername("orphan-" + UUID.randomUUID().toString().substring(0, 8));
        orphan.setPasswordHash("$dummy-hash-for-not-null");
        orphan.setEmail("orphan-clean@x.y");
        orphan.setRole("USER");
        orphan.setActive(false);
        orphan.setRegistrationStatus("PENDING_EMAIL_VERIFICATION");
        orphan.setCreatedAt(Instant.now().minusSeconds(30L * 3600L));
        orphan.setUpdatedAt(Instant.now());
        userRepository.save(orphan);
        cleanupIds.add(orphan.getId());

        assertThat(cleanupJob.cleanExpired()).isEqualTo(1);
        assertThat(userRepository.findById(orphan.getId())).isEmpty();
    }

    /**
     * WO-AUDIT-5 item 1: FK-blocker (api_key row referencing the user) makes
     * {@code userRepository.delete} fail AFTER {@code tokenRepository.deleteByUserId}.
     * Without per-user transactions the token delete commits alone (tokens gone, user
     * stuck); with them, BOTH roll back.
     */
    private void blockUserDeletion(UUID userId) {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(UUID.randomUUID());
        key.setOwnerUserId(userId);
        key.setKeyHash("blocker-" + UUID.randomUUID());
        key.setPrefix("zbpm_test_");
        key.setCreatedAt(Instant.now());
        apiKeyRepository.saveAndFlush(key);
        cleanupKeyIds.add(key.getId());
    }

    @Test
    void audit5_failedUserDelete_rollsBackTokenDelete() {
        UUID id = registeredStale("blocked", "blocked-clean@x.y", 25);
        assertThat(tokenCount(id)).isGreaterThan(0);
        blockUserDeletion(id);

        assertThat(cleanupJob.cleanExpired()).isEqualTo(0);

        // Rollback proof: the token row deleted first is still there, user too.
        // (Without per-user tx: tokens gone here → RED.)
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(tokenCount(id)).isGreaterThan(0);
    }

    @Test
    void audit5_oneBadRow_doesNotFailThePass() {
        UUID good = registeredStale("good", "good-clean@x.y", 25);
        UUID bad = registeredStale("bad", "bad-clean@x.y", 25);
        blockUserDeletion(bad);

        assertThat(cleanupJob.cleanExpired()).isEqualTo(1);

        // Clean row fully gone (user + tokens), blocked row fully kept (user + tokens).
        assertThat(userRepository.findById(good)).isEmpty();
        assertThat(tokenCount(good)).isEqualTo(0);
        assertThat(userRepository.findById(bad)).isPresent();
        assertThat(tokenCount(bad)).isGreaterThan(0);
    }
}
