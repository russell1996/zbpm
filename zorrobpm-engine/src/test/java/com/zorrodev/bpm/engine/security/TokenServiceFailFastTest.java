package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * WO-SEC-9: Fail-fast for default JWT secret in ALL profiles except dev/test.
 *  #1: No active profile + default secret → IllegalStateException
 *  #2: "production" profile + default secret → IllegalStateException
 *  #3: "test" profile + default secret → OK (test is in allowlist)
 *  #4: Any profile + custom secret → OK
 *  #6: proof-of-failure — test #1 on current code → no exception (RED)
 */
class TokenServiceFailFastTest {

    private static final String DEFAULT_SECRET = "change-me-dev-secret-please-override-in-production";
    private static final String CUSTOM_SECRET = "my-secure-secret-key-12345678";

    // --- Criterion #1: No active profile + default secret → fail-fast ---

    @Test
    void criterion1_noProfile_defaultSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        // No active profiles set
        assertThatThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    // --- Criterion #2: "production" profile + default secret → fail-fast ---

    @Test
    void criterion2_productionProfile_defaultSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    // --- Criterion #3: "test" profile + default secret → OK ---

    @Test
    void criterion3_testProfile_defaultSecret_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertThatNoException().isThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, env));
    }

    // --- Criterion #4: Custom secret → OK regardless of profile ---

    @Test
    void criterion4_customSecret_anyProfile_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatNoException().isThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, env));
    }

    @Test
    void criterion4_noProfile_customSecret_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        assertThatNoException().isThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, env));
    }
}
