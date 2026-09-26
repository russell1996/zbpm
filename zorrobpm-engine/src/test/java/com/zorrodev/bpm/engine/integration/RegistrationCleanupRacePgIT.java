package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.RegistrationCleanupJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-39 criterion 3 (F19), PostgreSQL only: the stale-candidate list that
 * {@code RegistrationCleanupJob} reads is a snapshot — between that read and the
 * per-row delete the row may be decided (admin approved it). The job is parked on
 * a latch wedged between the list read and the row deletes (via the
 * {@code afterListReadHook} test seam); the main thread flips the user to ACTIVE
 * while the job waits. The job must re-load the row under {@code FOR UPDATE}
 * inside its own transaction, see the live ACTIVE status and skip the row —
 * never delete a decided registration.
 *
 * <p>POF (G-N): reverting the per-row re-read/re-check in
 * {@code RegistrationCleanupJob.cleanExpired} (deleting the snapshot row
 * blindly) makes this RED — the ACTIVE user is deleted.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class RegistrationCleanupRacePgIT extends PostgresIT {

    @Autowired private RegistrationCleanupJob cleanupJob;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private StubMailSender mailSender;

    private final List<UUID> cleanupIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        cleanupJob.setAfterListReadHook(null);
        mailSender.clear();
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupIds.clear();
    }

    @Test
    void statusChangedBetweenListAndDelete_rowSurvives() throws Exception {
        // A stale PENDING_EMAIL_VERIFICATION row the job WILL list...
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("rel39-cu-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("hashed");
        u.setFullName("Cleanup Race");
        u.setEmail("rel39-cu-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        u.setRole("USER");
        u.setActive(false);
        u.setUserType("HUMAN");
        u.setCreatedAt(Instant.now().minusSeconds(30L * 3600L));
        u.setUpdatedAt(Instant.now());
        u.setRegistrationStatus("PENDING_EMAIL_VERIFICATION");
        userRepository.save(u);
        cleanupIds.add(u.getId());

        // ...but the hook parks the job AFTER the list read, BEFORE the row deletes.
        CountDownLatch listRead = new CountDownLatch(1);
        CountDownLatch mutateGate = new CountDownLatch(1);
        cleanupJob.setAfterListReadHook(() -> {
            listRead.countDown();
            try {
                assertThat(mutateGate.await(20, TimeUnit.SECONDS))
                    .as("mutation gate must open")
                    .isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        AtomicInteger deleted = new AtomicInteger(-1);
        AtomicReference<Throwable> jobError = new AtomicReference<>();
        Thread job = new Thread(() -> {
            try {
                deleted.set(cleanupJob.cleanExpired());
            } catch (Throwable t) {
                jobError.set(t);
            }
        });
        job.start();

        // While the job is parked: the row gets decided (admin approved it).
        assertThat(listRead.await(20, TimeUnit.SECONDS)).isTrue();
        UiUserEntity decided = userRepository.findById(u.getId()).orElseThrow();
        decided.setRegistrationStatus("ACTIVE");
        decided.setActive(true);
        decided.setApprovedAt(Instant.now());
        userRepository.save(decided);
        mutateGate.countDown();
        job.join(30000);

        assertThat(jobError.get()).isNull();
        // No assertion on the total deleted count: the shared PG database may
        // hold foreign stale rows — the criterion is OUR decided row surviving.
        UiUserEntity survivor = userRepository.findById(u.getId()).orElseThrow();
        assertThat(survivor.getRegistrationStatus()).isEqualTo("ACTIVE");
        assertThat(survivor.isActive()).isTrue();
    }

    @Test
    void genuinelyStaleRow_stillDeleted() {
        // Guard against an over-correction: a row that is STILL stale at delete
        // time must keep being deleted (same hook path, no concurrent mutation).
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("rel39-cs-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("hashed");
        u.setFullName("Cleanup Still Stale");
        u.setEmail("rel39-cs-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        u.setRole("USER");
        u.setActive(false);
        u.setUserType("HUMAN");
        u.setCreatedAt(Instant.now().minusSeconds(30L * 3600L));
        u.setUpdatedAt(Instant.now());
        u.setRegistrationStatus("PENDING_EMAIL_VERIFICATION");
        userRepository.save(u);
        cleanupIds.add(u.getId());

        // >= 1, not == 1: the shared PG database may hold stale rows left by
        // other suites running in the same fork — what matters is OUR row is gone.
        assertThat(cleanupJob.cleanExpired()).isGreaterThanOrEqualTo(1);
        assertThat(userRepository.findById(u.getId())).isEmpty();
    }
}
