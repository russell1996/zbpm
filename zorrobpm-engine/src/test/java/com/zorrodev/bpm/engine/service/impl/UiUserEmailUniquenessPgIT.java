package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REG-1 (PostgreSQL level): the case-insensitive unique index is the last line
 * of defense — proven by direct inserts bypassing the service, by service conflicts,
 * by SYSTEM NULL coexistence (partial index), and by a real two-thread race.
 * H2 cannot express the partial functional index (proven live on H2 2.4.240), so
 * these run here, not in {@code UiUserEmailNormalizationTests}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class UiUserEmailUniquenessPgIT extends PostgresIT {

    @Autowired private UiUserServiceImpl userService;
    @Autowired private UiUserRepository userRepository;

    private final List<UUID> cleanupIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupIds.clear();
    }

    private CreateUiUserDTO human(String username, String email) {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setCreationMode("PASSWORD");
        dto.setEmail(email);
        return dto;
    }

    private UiUserEntity row(String username, String email) {
        UiUserEntity entity = new UiUserEntity();
        entity.setId(UUID.randomUUID());
        entity.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setPasswordHash("hashed");
        entity.setFullName("pg");
        entity.setEmail(email);
        entity.setRole("USER");
        entity.setActive(true);
        entity.setUserType("HUMAN");
        entity.setForcePasswordChange(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    @Test
    void serviceConflict_caseVariant_conflictsOnPG() {
        cleanupIds.add(userService.create(human("pgfirst", "foo@x.com")));

        CreateUiUserDTO clash = human("pgsecond", "FOO@X.com");
        assertThatThrownBy(() -> userService.create(clash))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Email already exists");
    }

    @Test
    void dbConstraint_directInserts_conflictOnPG() {
        UiUserEntity first = row("pgdbfirst", "foo@x.com");
        userRepository.saveAndFlush(first);
        cleanupIds.add(first.getId());

        UiUserEntity second = row("pgdbsecond", "FOO@X.com");
        assertThatThrownBy(() -> {
            userRepository.saveAndFlush(second);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void systemNullEmails_coexistOnPG() {
        UiUserEntity first = row("pgsysfirst", null);
        first.setUserType("SYSTEM");
        userRepository.saveAndFlush(first);
        cleanupIds.add(first.getId());

        UiUserEntity second = row("pgsyssecond", null);
        second.setUserType("SYSTEM");
        userRepository.saveAndFlush(second);
        cleanupIds.add(second.getId());

        assertThat(userRepository.findById(first.getId())).isPresent();
        assertThat(userRepository.findById(second.getId())).isPresent();
    }

    @Test
    void concurrentCreates_sameEmailCaseVariants_oneWins() throws Exception {
        // V6: two REAL threads/transactions racing the same address in different cases.
        // The unique index guarantees at most one row; the loser must see the domain
        // conflict (via the service check or the translated violation), never a raw 500.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<UUID> winner = new AtomicReference<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable attempt = () -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) {
                    failures.add(new IllegalStateException("latch timeout"));
                    return;
                }
                UUID id = userService.create(human("pgrace", "Race@X.com"));
                cleanupIds.add(id);
                winner.compareAndSet(null, id);
            } catch (Throwable e) {
                failures.add(e);
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

        long created = userRepository.findAll().stream()
            .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase("race@x.com"))
            .count();
        assertThat(created).isEqualTo(1);
        assertThat(winner.get()).isNotNull();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(EngineException.class)
            .hasMessageContaining("Email already exists");
    }
}
