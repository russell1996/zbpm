package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * WO-REG-7 (P2 hardening): TTL cleanup of stuck self-registrations.
 *
 * <p>Accounts stuck in {@code PENDING_EMAIL_VERIFICATION} past the email-verify
 * window would otherwise pile up in the DB forever, holding their unique
 * email/username hostage so the real owner of the address can never register.
 * This job deletes them outright (not flags them) together with their
 * {@code EMAIL_VERIFY} token rows — the address becomes registrable again.
 *
 * <p>TTL source: the existing {@code zorrobpm.security.email-verify-ttl-hours}
 * (default 24) — deliberately NOT a second key. The WO text proposes a new
 * {@code registration-verify-ttl-hours}, but a separate deletion TTL that
 * anyone tunes below the token TTL would delete accounts whose verify links
 * are still valid. One key makes that bug class impossible: "stale" means
 * exactly "past the window its own token already expired in".
 *
 * <p>PasswordTokenEntity has no shared expired-token sweeper (checked before
 * writing this — there is no @Scheduled touching that table), so no second
 * job is started in vain: this one deletes the tokens of exactly the users
 * it deletes, nothing else (RESET/INVITE tokens of live users are out of
 * scope, V7).
 *
 * <p>Deletion is per-row with continue-on-error (P-42): one bad row (lock,
 * concurrent admin action) logs a warning, the pass continues. Only
 * {@code PENDING_EMAIL_VERIFICATION} rows are ever eligible — every other
 * status is structurally excluded by the query, not by an if.
 */
@Slf4j
@Component
public class RegistrationCleanupJob {

    private final UiUserRepository userRepository;
    private final PasswordTokenRepository tokenRepository;

    @Value("${zorrobpm.security.email-verify-ttl-hours:24}")
    private int verifyTtlHours = 24;

    public RegistrationCleanupJob(UiUserRepository userRepository,
            PasswordTokenRepository tokenRepository) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.registration.cleanup-interval-ms:3600000}")
    public void run() {
        int deleted = cleanExpired();
        if (deleted > 0) {
            log.info("RegistrationCleanup: deleted {} stale unverified registrations", deleted);
        }
    }

    /**
     * Deletes PENDING_EMAIL_VERIFICATION users older than the verify TTL with
     * their token rows. Returns the deleted count. Public for tests (the
     * {@code @Scheduled} entry point is {@link #run()}).
     */
    public int cleanExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(verifyTtlHours));
        List<UiUserEntity> stale = userRepository
            .findByRegistrationStatusAndCreatedAtBefore("PENDING_EMAIL_VERIFICATION", cutoff);
        int deleted = 0;
        for (UiUserEntity user : stale) {
            try {
                tokenRepository.deleteByUserId(user.getId());
                userRepository.delete(user);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("RegistrationCleanup: failed to delete stale registration {} — continuing with the rest",
                    user.getId(), e);
            }
        }
        return deleted;
    }
}
