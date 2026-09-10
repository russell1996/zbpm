package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-5 item 2 (C5), PostgreSQL only: two REAL threads register the SAME
 * username with DIFFERENT emails — the unique constraint guarantees exactly one
 * row no matter the interleaving; the loser sees a domain 409
 * ({@code USERNAME_ALREADY_EXISTS}), never a raw 500. Mirrors
 * {@code SelfRegistrationRacePgIT} (same-username instead of same-email).
 * H2 cannot prove this the same way (the PG race surfaces as JpaSystemException
 * 25P02, handled by the same catch), so this lives here.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class UsernameRegistrationRacePgIT extends PostgresIT {

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
    void parallelRegister_sameUsername_differentEmails_oneRowLoserSees409() throws Exception {
        String username = "urace-" + UUID.randomUUID().toString().substring(0, 8);
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
                dto.setUsername(username);
                dto.setPassword("MyStr0ng!P@ssw0rd");
                dto.setFullName("Race");
                dto.setEmail("urace-" + UUID.randomUUID() + "@x.com");
                registrationService.register(dto, "10.9.1." + (Thread.currentThread().getId() % 200 + 1));
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
            .filter(u -> username.equals(u.getUsername()))
            .toList();
        assertThat(rows).hasSize(1);
        cleanupIds.add(rows.get(0).getId());
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(ApiException.class);
        ApiException conflict = (ApiException) failures.get(0);
        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getCode()).isEqualTo("USERNAME_ALREADY_EXISTS");
    }
}
