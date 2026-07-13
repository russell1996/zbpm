package com.zorrodev.bpm.engine.security;

import com.zorrodev.bpm.engine.repository.UiUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * WO-SEC-14: In prod, reject startup if ZORROBPM_DEFAULT_ADMIN_PASSWORD
 * is missing or equals the default "admin".
 */
@Slf4j
@Component
public class AdminPasswordValidator {

    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    private static final Set<String> PASSWORD_OPTIONAL_PROFILES = Set.of("dev", "test");

    public AdminPasswordValidator(
        @Value("${zorrobpm.security.default-admin-password:admin}") String password,
        Environment environment) {

        boolean devOrTest = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(PASSWORD_OPTIONAL_PROFILES::contains);
        if (devOrTest) return;

        if (DEFAULT_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be set to a secure value "
                + "in production (default 'admin' is not allowed). "
                + "Set ZORROBPM_DEFAULT_ADMIN_PASSWORD or application-prod.yml.");
        }
        log.info("Admin password configured for production");
    }
}
