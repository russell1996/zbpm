package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.AdminPasswordValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-14 Criterion #1: Prod with default admin password → fail-fast.
 * WO-SEC-14 Criterion #2: Dev/test starts normally with default admin password.
 */
class AdminPasswordFailFastIT {

    @Test
    void prodProfile_withDefaultPassword_shouldFailFast() {
        Environment env = new StandardEnvironment();
        assertThatThrownBy(() -> new AdminPasswordValidator("admin", env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("default-admin-password");
    }

    @Test
    void prodProfile_withSecurePassword_shouldStart() {
        Environment env = new StandardEnvironment();
        // Should not throw
        new AdminPasswordValidator("super-secure-password-42", env);
    }

    @Test
    void testProfile_withDefaultPassword_shouldStart() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.addActiveProfile("test");
        // Should not throw — default password allowed in test profile
        new AdminPasswordValidator("admin", env);
    }
}
