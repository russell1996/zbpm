package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ProcessSubmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
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
 * WO-REL-39 criteria 1-2 (F18), PostgreSQL only: two REAL threads decide the same
 * PENDING submission at once (approve vs reject, approve vs approve). The row lock
 * ({@code ProcessSubmissionRepository.findByIdForUpdate}) serialises them — exactly
 * one decision wins, the loser gets 409 SUBMISSION_ALREADY_REVIEWED, and the side
 * effects (deployed definition, registry process, OWNER membership) are consistent
 * with the winner: no definition/OWNER left behind a REJECTED submission, no
 * double process/owner on a double approve.
 *
 * <p>POF (G-N): reverting {@code findByIdForUpdate} to {@code findById} in
 * {@code ProcessSubmissionServiceImpl.requirePending} makes this RED — both
 * threads read PENDING and both act (orphaned deploy on REJECT, or a raw 500 /
 * doubled process on double approve).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class SubmissionDecisionRacePgIT extends PostgresIT {

    private static final int ROUNDS = 3;

    @Autowired private ProcessSubmissionService submissionService;
    @Autowired private ProcessSubmissionRepository submissionRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private UiUserRepository userRepository;

    private final List<UUID> cleanupUserIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> cleanupSubmissionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> cleanupKeys = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> cleanupDefinitionIds = Collections.synchronizedList(new ArrayList<>());
    private String bpmnTemplate;
    private UUID adminId;
    private UUID submitterId;

    @AfterEach
    void cleanup() {
        for (UUID sid : List.copyOf(cleanupSubmissionIds)) {
            try {
                submissionRepository.deleteById(sid);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupSubmissionIds.clear();
        for (String key : List.copyOf(cleanupKeys)) {
            try {
                processRepository.findByDefinitionKey(key).ifPresent(p -> {
                    processMemberRepository.findByProcessId(p.getId())
                        .forEach(processMemberRepository::delete);
                    processRepository.delete(p);
                });
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupKeys.clear();
        for (UUID did : List.copyOf(cleanupDefinitionIds)) {
            try {
                processDefinitionRepository.deleteById(did);
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupDefinitionIds.clear();
        for (UUID uid : List.copyOf(cleanupUserIds)) {
            try {
                userRepository.deleteById(uid);
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupUserIds.clear();
    }

    private void users() {
        if (adminId != null) {
            return;
        }
        adminId = seedUser("rel39-subadm", "SUPER_ADMIN");
        submitterId = seedUser("rel39-subsub", "USER");
    }

    private UUID seedUser(String prefix, String role) {
        UiUserEntity e = new UiUserEntity();
        e.setId(UUID.randomUUID());
        e.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setPasswordHash("hashed");
        e.setFullName("Race");
        e.setEmail(prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        e.setRole(role);
        e.setActive(true);
        e.setUserType("HUMAN");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setRegistrationStatus("ACTIVE");
        userRepository.save(e);
        cleanupUserIds.add(e.getId());
        return e.getId();
    }

    private String template() throws Exception {
        if (bpmnTemplate == null) {
            bpmnTemplate = Files.readString(Paths.get("src/test/files/process1.bpmn"));
        }
        return bpmnTemplate;
    }

    private String bpmnFor(String key) throws Exception {
        return template()
            .replace("id=\"process1\"", "id=\"" + key + "\"")
            .replace("name=\"Process 1\"", "name=\"" + key + "\"");
    }

    /** Seeds a PENDING submission straight in the DB (same shape as the service creates). */
    private UUID seedPending(String key) throws Exception {
        ProcessSubmissionEntity entity = new ProcessSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setBpmn(bpmnFor(key));
        entity.setProcessKey(key);
        entity.setName(key);
        entity.setSubmittedBy(submitterId);
        entity.setSubmittedAt(Instant.now());
        entity.setStatus("PENDING");
        submissionRepository.save(entity);
        cleanupSubmissionIds.add(entity.getId());
        return entity.getId();
    }

    private Principal admin() {
        return new Principal.UserPrincipal(adminId, "rel39-subadm", "SUPER_ADMIN");
    }

    private void assertLoser409(Throwable t, String where) {
        assertThat(t)
            .as("%s: loser gets 409 ALREADY_REVIEWED, not a silent overwrite", where)
            .isInstanceOf(ApiException.class)
            .satisfies(th -> {
                ApiException ae = (ApiException) th;
                assertThat(ae.getStatus().value()).isEqualTo(409);
                assertThat(ae.getCode()).isEqualTo("SUBMISSION_ALREADY_REVIEWED");
            });
    }

    @Test
    void approveVsReject_exactlyOneWins_artifactsMatchWinner() throws Exception {
        users();
        Principal adm = admin();
        for (int round = 0; round < ROUNDS; round++) {
            String key = "rel39r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            UUID sid = seedPending(key);
            cleanupKeys.add(key);

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
                    var dto = submissionService.approve(sid, adm);
                    if (dto.getApprovedDefinitionId() != null) {
                        cleanupDefinitionIds.add(dto.getApprovedDefinitionId());
                    }
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
                    submissionService.reject(sid, "race-reject", adm);
                    winner.compareAndSet(null, "reject");
                } catch (Throwable t) {
                    losses.add(t);
                }
            });
            approve.start();
            reject.start();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            approve.join(60000);
            reject.join(60000);

            assertThat(winner.get())
                .as("round %d: exactly one decision won", round)
                .isNotNull();
            assertThat(losses)
                .as("round %d: exactly one loser", round)
                .hasSize(1);
            assertLoser409(losses.get(0), "round " + round);

            ProcessSubmissionEntity fin = submissionRepository.findById(sid).orElseThrow();
            if ("approve".equals(winner.get())) {
                assertThat(fin.getStatus()).isEqualTo("APPROVED");
                assertThat(fin.getApprovedDefinitionId()).isNotNull();
                assertThat(processRepository.findByDefinitionKey(key)).isPresent();
                assertThat(processMemberRepository.findByProcessId(
                    processRepository.findByDefinitionKey(key).orElseThrow().getId()))
                    .anyMatch(m -> submitterId.equals(m.getUserId()) && "OWNER".equals(m.getRole()));
            } else {
                assertThat(fin.getStatus()).isEqualTo("REJECTED");
                assertThat(fin.getRejectReason()).isEqualTo("race-reject");
                assertThat(fin.getApprovedDefinitionId()).isNull();
                // No orphaned deploy/registry rows behind a REJECTED submission.
                assertThat(processRepository.findByDefinitionKey(key))
                    .as("round %d: reject winner leaves no process", round)
                    .isEmpty();
                assertThat(processDefinitionRepository.findAll(
                    (root, q, cb) -> cb.equal(root.get("key"), key)))
                    .as("round %d: reject winner leaves no definition", round)
                    .isEmpty();
            }
        }
    }

    @Test
    void approveVsApprove_singleProcess_singleOwner_loserGets409() throws Exception {
        users();
        Principal adm = admin();
        for (int round = 0; round < ROUNDS; round++) {
            String key = "rel39a_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            UUID sid = seedPending(key);
            cleanupKeys.add(key);

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
                    var dto = submissionService.approve(sid, adm);
                    if (dto.getApprovedDefinitionId() != null) {
                        cleanupDefinitionIds.add(dto.getApprovedDefinitionId());
                    }
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
            first.join(60000);
            second.join(60000);

            assertThat(winner.get()).isEqualTo("approve");
            assertThat(losses).hasSize(1);
            assertLoser409(losses.get(0), "round " + round + " double-approve");

            ProcessSubmissionEntity fin = submissionRepository.findById(sid).orElseThrow();
            assertThat(fin.getStatus()).isEqualTo("APPROVED");
            UUID pid = processRepository.findByDefinitionKey(key).orElseThrow().getId();
            // No double effect: exactly one OWNER membership for the submitter.
            assertThat(processMemberRepository.findByProcessId(pid).stream()
                .filter(m -> submitterId.equals(m.getUserId()) && "OWNER".equals(m.getRole()))
                .count()).isEqualTo(1);
        }
    }
}
