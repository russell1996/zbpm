package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RegistrationAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-39 criterion 1 (F18), PostgreSQL only: two REAL threads decide the same
 * PENDING_APPROVAL registration at once (approve vs reject). The row lock
 * ({@code UiUserRepository.findByIdForUpdate}) serialises them — exactly one
 * decision wins, the loser sees the decided status and gets 409
 * REGISTRATION_ALREADY_DECIDED, never a silent overwrite. The final row and the
 * notification mails are consistent with the winner.
 *
 * <p>POF (G-N): reverting {@code findByIdForUpdate} to {@code findById} in
 * {@code RegistrationAdminService} makes this RED — both threads read PENDING
 * and both write (winner's decision silently overwritten, two mails sent).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class RegistrationDecisionRacePgIT extends PostgresIT {

    private static final int ROUNDS = 6;

    @Autowired private RegistrationAdminService adminService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private StubMailSender mailSender;

    private final List<UUID> cleanupIds = Collections.synchronizedList(new ArrayList<>());
    private UUID adminId;

    @AfterEach
    void cleanup() {
        mailSender.clear();
        // FK approved_by points at the admin: delete decided users first, admin last.
        for (UUID id : List.copyOf(cleanupIds)) {
            if (id.equals(adminId)) {
                continue;
            }
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        if (adminId != null) {
            try {
                userRepository.deleteById(adminId);
            } catch (Exception e) {
                // best effort
            }
            adminId = null;
        }
        cleanupIds.clear();
    }

    private Principal superAdmin() {
        if (adminId == null) {
            UiUserEntity e = new UiUserEntity();
            e.setId(UUID.randomUUID());
            e.setUsername("rel39-sup-" + UUID.randomUUID().toString().substring(0, 8));
            e.setPasswordHash("hashed");
            e.setFullName("Super");
            e.setEmail("rel39-sup-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
            e.setRole("SUPER_ADMIN");
            e.setActive(true);
            e.setUserType("HUMAN");
            e.setForcePasswordChange(false);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            e.setRegistrationStatus("ACTIVE");
            userRepository.save(e);
            adminId = e.getId();
            cleanupIds.add(adminId);
        }
        return new Principal.UserPrincipal(adminId, "rel39-sup", "SUPER_ADMIN");
    }

    /** Seeds a decision-ready registration straight in PENDING_APPROVAL. */
    private UUID seedPendingApproval(String tag) {
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("rel39-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("hashed");
        u.setFullName("Race User");
        u.setEmail("rel39-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        u.setRole("USER");
        u.setActive(false);
        u.setUserType("HUMAN");
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        u.setRegistrationStatus("PENDING_APPROVAL");
        u.setEmailVerifiedAt(Instant.now());
        userRepository.save(u);
        cleanupIds.add(u.getId());
        return u.getId();
    }

    private long mailsTo(String email, String subjectPart) {
        return mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .filter(m -> m.subject() != null && m.subject().contains(subjectPart))
            .count();
    }

    @Test
    void approveVsReject_exactlyOneWins_loserGets409_stateMatchesWinner() throws Exception {
        Principal sup = superAdmin();
        for (int round = 0; round < ROUNDS; round++) {
            UUID userId = seedPendingApproval("ar");
            String email = userRepository.findById(userId).orElseThrow().getEmail();
            mailSender.clear();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<String> winner = new AtomicReference<>();
            List<Throwable> losses = Collections.synchronizedList(new ArrayList<>());

            Thread approve = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        losses.add(new IllegalStateException("latch timeout"));
                        return;
                    }
                    adminService.approveRegistration(userId, sup);
                    winner.compareAndSet(null, "approve");
                } catch (Throwable t) {
                    losses.add(t);
                }
            });
            Thread reject = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        losses.add(new IllegalStateException("latch timeout"));
                        return;
                    }
                    adminService.rejectRegistration(userId, "race-reject", sup);
                    winner.compareAndSet(null, "reject");
                } catch (Throwable t) {
                    losses.add(t);
                }
            });
            approve.start();
            reject.start();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            approve.join(30000);
            reject.join(30000);

            assertThat(winner.get())
                .as("round %d: exactly one decision won", round)
                .isNotNull();
            assertThat(losses)
                .as("round %d: exactly one loser", round)
                .hasSize(1);
            assertThat(losses.get(0))
                .as("round %d: loser gets 409 ALREADY_DECIDED, not a silent overwrite", round)
                .isInstanceOf(ApiException.class)
                .satisfies(t -> {
                    ApiException ae = (ApiException) t;
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                    assertThat(ae.getCode()).isEqualTo("REGISTRATION_ALREADY_DECIDED");
                });

            UiUserEntity fin = userRepository.findById(userId).orElseThrow();
            if ("approve".equals(winner.get())) {
                assertThat(fin.getRegistrationStatus()).isEqualTo("ACTIVE");
                assertThat(fin.isActive()).isTrue();
                assertThat(fin.getApprovedAt()).isNotNull();
                assertThat(fin.getApprovedBy()).isEqualTo(adminId);
                assertThat(fin.getRejectedAt()).isNull();
                assertThat(mailsTo(email, "одобрена")).isEqualTo(1);
                assertThat(mailsTo(email, "отклонена")).isEqualTo(0);
            } else {
                assertThat(fin.getRegistrationStatus()).isEqualTo("REJECTED");
                assertThat(fin.isActive()).isFalse();
                assertThat(fin.getRejectedAt()).isNotNull();
                assertThat(fin.getRejectedReason()).isEqualTo("race-reject");
                assertThat(fin.getApprovedAt()).isNull();
                assertThat(mailsTo(email, "отклонена")).isEqualTo(1);
                assertThat(mailsTo(email, "одобрена")).isEqualTo(0);
            }
        }
    }

    @Test
    void approveVsApprove_singleEffect_loserGets409() throws Exception {
        Principal sup = superAdmin();
        for (int round = 0; round < 4; round++) {
            UUID userId = seedPendingApproval("aa");
            String email = userRepository.findById(userId).orElseThrow().getEmail();
            mailSender.clear();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<String> winner = new AtomicReference<>();
            List<Throwable> losses = Collections.synchronizedList(new ArrayList<>());

            Runnable attempt = () -> {
                ready.countDown();
                try {
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        losses.add(new IllegalStateException("latch timeout"));
                        return;
                    }
                    adminService.approveRegistration(userId, sup);
                    winner.compareAndSet(null, "approve");
                } catch (Throwable t) {
                    losses.add(t);
                }
            };
            Thread first = new Thread(attempt);
            Thread second = new Thread(attempt);
            first.start();
            second.start();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            first.join(30000);
            second.join(30000);

            assertThat(winner.get()).isEqualTo("approve");
            assertThat(losses).hasSize(1);
            assertThat(losses.get(0)).isInstanceOf(ApiException.class)
                .satisfies(t -> {
                    ApiException ae = (ApiException) t;
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                    assertThat(ae.getCode()).isEqualTo("REGISTRATION_ALREADY_DECIDED");
                });

            UiUserEntity fin = userRepository.findById(userId).orElseThrow();
            assertThat(fin.getRegistrationStatus()).isEqualTo("ACTIVE");
            assertThat(fin.isActive()).isTrue();
            // No double effect: exactly one approval mail, even though approve ran twice.
            assertThat(mailsTo(email, "одобрена")).isEqualTo(1);
            assertThat(mailsTo(email, "отклонена")).isEqualTo(0);
        }
    }
}
