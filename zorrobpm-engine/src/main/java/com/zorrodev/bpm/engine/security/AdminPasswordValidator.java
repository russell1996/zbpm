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
 * WO-SEC-14 + WO-SEC-31c: In prod, reject startup if admin password is weak.
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
    private static final Set<String> PROD_REQUIRED_PROFILES = Set.of("prod");
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

        boolean isProd = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(PROD_REQUIRED_PROFILES::contains);
        if (!isProd) return;

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
