package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WO-OBS-6: the admin seed must not depend on seeder ordering.
 *
 * <p>{@code UiUserBootstrap} used to gate on {@code repository.count() > 0} —
 * the first {@code ScrapeTokenBootstrap} run (any other seeder, any order)
 * suppressed the admin seed and the console was unreachable out of the box
 * (caught live: admin login 401 when both runners share a context). The gate
 * is now the admin row itself.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiUserBootstrapTest {

    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired UiUserBootstrap bootstrap;

    @Test
    void adminSeeded_evenWhenOtherUsersExist() {
        // Contested state: users exist (whatever seeder ran first), admin row absent.
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);
        assertFalse(userRepository.existsByUsername("admin"));

        UiUserEntity other = new UiUserEntity();
        other.setId(UUID.randomUUID());
        other.setUsername("other-seeder-" + UUID.randomUUID().toString().substring(0, 8));
        other.setPasswordHash(passwordHasher.hash("x"));
        other.setRole("USER");
        other.setActive(true);
        other.setUserType("SYSTEM");
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        userRepository.save(other);

        bootstrap.run(null);

        assertTrue(userRepository.existsByUsername("admin"),
            "admin seed must not depend on other seeders having run first");
    }

    @Test
    void adminSeed_idempotentWhenPresent() {
        bootstrap.run(null);
        bootstrap.run(null);

        long admins = userRepository.findAll().stream()
            .filter(u -> "admin".equals(u.getUsername()))
            .count();
        assertEquals(1, admins, "repeated runs must not duplicate the admin");
    }
}
