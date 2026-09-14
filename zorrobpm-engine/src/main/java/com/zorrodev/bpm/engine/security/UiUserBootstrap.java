package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Seeds a default admin account on first startup so the console is reachable out of the box. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UiUserBootstrap implements ApplicationRunner {

    private final UiUserRepository repository;
    private final PasswordHasher passwordHasher;

    @Value("${zorrobpm.security.default-admin-username:admin}")
    private String adminUsername;

    @Value("${zorrobpm.security.default-admin-password:admin}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        // WO-OBS-6: gate on the admin row itself, not on "any user exists" — other
        // seeders (e.g. ScrapeTokenBootstrap) may run first and must not suppress
        // the admin seed; same for a restored DB that has users but no admin row.
        if (repository.existsByUsername(adminUsername)) return;

        UiUserEntity admin = new UiUserEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordHasher.hash(adminPassword));
        admin.setFullName("Administrator");
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setForcePasswordChange(true);
        admin.setUserType("HUMAN");
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        repository.save(admin);

        log.warn("Seeded default UI admin user '{}'. Change its password after first login "
            + "(or set zorrobpm.security.default-admin-password).", adminUsername);
    }
}
