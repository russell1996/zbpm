package com.zorrodev.bpm.engine.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WO-SEC-14 + WO-SEC-31c: reject startup if admin password is weak.
 *
 * WO-SEC-68: the check runs on EVERY profile EXCEPT the explicit safe list
 * ({@code dev}, {@code test} — same allowlist-exception as
 * {@code TokenService.SECRET_OPTIONAL_PROFILES}). The previous prod-only gate
 * silently skipped validation on any non-prod launch (default profile, staging
 * without the exact {@code prod} name, forgotten deploy flag).
 *
 * Checks:
 *  - Password must differ from default "admin"
 *  - Password must be >= 12 characters
 *  - Password must not be in a blocklist of commonly weak passwords
 *
 * Uses BeanFactoryPostProcessor to run BEFORE bean instantiation,
 * ensuring the check happens before any other bean can fail.
 */
@Slf4j
@Component
public class AdminPasswordValidator implements BeanFactoryPostProcessor {

    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    /**
     * WO-SEC-68: allowlist-EXCEPTION — validation is SKIPPED only on these
     * profiles (local dev / CI). Everywhere else (prod, staging, default
     * profile with no explicit name) it runs. Same set as
     * {@code TokenService.SECRET_OPTIONAL_PROFILES} — keep in sync.
     */
    private static final Set<String> SAFE_PROFILES = Set.of("dev", "test");
    private static final int MIN_PASSWORD_LENGTH = 12;

    /** WO-SEC-31c: blocklist of commonly weak passwords that must be rejected. */
    private static final Set<String> WEAK_PASSWORD_BLOCKLIST = Set.of(
        "admin", "password", "zorrodev", "123456",
        "qwerty", "letmein", "welcome", "monkey", "dragon",
        "master", "abc123", "passw0rd", "changeme", "default",
        "root", "toor", "test", "demo", "sample"
    );

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean safeProfile = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) return;

        String password = Binder.get(environment)
            .bind("zorrobpm.security.default-admin-password", Bindable.of(String.class))
            .orElse(DEFAULT_ADMIN_PASSWORD);

        if (DEFAULT_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be set to a secure value "
                + "in production (default 'admin' is not allowed). "
                + "Set ZORROBPM_DEFAULT_ADMIN_PASSWORD or application-prod.yml.");
        }

        // WO-SEC-31c: minimum length
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be at least "
                + MIN_PASSWORD_LENGTH + " characters.");
        }

        // WO-SEC-31c: blocklist check
        if (WEAK_PASSWORD_BLOCKLIST.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password is a known weak password. "
                + "Choose a strong, unique password for production.");
        }

        log.info("Admin password configured for production");
    }

    /**
     * WO-SEC-46: check if a password is weak (too short or blocklisted).
     * Public — reusable from UiUserServiceImpl for create/update validation.
     */
    public static boolean isWeak(String password) {
        if (password == null) return true;
        if (password.length() < MIN_PASSWORD_LENGTH) return true;
        return WEAK_PASSWORD_BLOCKLIST.contains(password.toLowerCase(java.util.Locale.ROOT));
    }
}
