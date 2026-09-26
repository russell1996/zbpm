package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REG-3 criterion 4 (race half), PostgreSQL only: two REAL threads register the
 * same address in different cases — the partial unique index guarantees exactly one
 * row no matter the interleaving; the loser sees the domain conflict, never a raw
 * 500 and never a silent second account. H2 cannot prove this (no partial functional
 * index there), so this lives here, not in {@code SelfRegistrationIntegrationTests}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class SelfRegistrationRacePgIT extends PostgresIT {

    @Autowired private SelfRegistrationService registrationService;
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

    @Test
    void parallelRegister_sameEmailCaseVariants_oneRowNoSilentDouble() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable attempt = () -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) {
                    failures.add(new IllegalStateException("latch timeout"));
                    return;
                }
                RegisterDTO dto = new RegisterDTO();
                dto.setUsername("regrace-" + UUID.randomUUID().toString().substring(0, 8));
                dto.setPassword("MyStr0ng!P@ssw0rd");
                dto.setFullName("Race");
                dto.setEmail("RaceReg@X.com");
                registrationService.register(dto, "10.9.0." + (Thread.currentThread().getId() % 200 + 1));
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

        List<UiUserEntity> rows = userRepository.findAll().stream()
            .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase("racereg@x.com"))
            .toList();
        assertThat(rows).hasSize(1);
        cleanupIds.add(rows.get(0).getId());
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(EngineException.class);
    }
}
