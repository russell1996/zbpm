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
    // >= MIN_SECRET_LENGTH (WO-SEC-63 F20): a "custom" secret must also be long enough to be a real key
    private static final String CUSTOM_SECRET = "my-secure-secret-key-0123456789abcdef";
    // legacy CSV entries must satisfy the SAME length rule as the active secret
    private static final String LEGACY_A = "legacy-key-a-secret-0123456789abcdef";
    private static final String LEGACY_B = "legacy-key-b-secret-0123456789abcdef";

    // --- Criterion #1: No active profile + default secret → fail-fast ---

    @Test
    void criterion1_noProfile_defaultSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        // No active profiles set
        assertThatThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, "", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    // --- Criterion #2: "production" profile + default secret → fail-fast ---

    @Test
    void criterion2_productionProfile_defaultSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, "", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    // --- Criterion #3: "test" profile + default secret → OK ---

    @Test
    void criterion3_testProfile_defaultSecret_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertThatNoException().isThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, "", env));
    }

    // --- Criterion #4: Custom secret → OK regardless of profile ---

    @Test
    void criterion4_customSecret_anyProfile_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatNoException().isThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, "", env));
    }

    @Test
    void criterion4_noProfile_customSecret_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        assertThatNoException().isThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, "", env));
    }

    // --- P-41 (WO-SEC-57 HOLD): legacy secrets must pass the same fail-fast as the active one ---
    // The default secret, written into jwt-legacy-secrets, would otherwise become an accepted
    // signing key in production — a full bypass of the WO-SEC-9 invariant.

    @Test
    void criterion5_productionProfile_defaultSecretInLegacy_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, DEFAULT_SECRET, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-legacy-secrets");
    }

    @Test
    void criterion5_noProfile_defaultSecretInLegacy_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        assertThatThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, DEFAULT_SECRET, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-legacy-secrets");
    }

    @Test
    void criterion5_defaultSecretInLegacy_withCustomEntries_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, "legacy-key-1," + DEFAULT_SECRET + ",legacy-key-2", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-legacy-secrets");
    }

    @Test
    void criterion5_testProfile_defaultSecretInLegacy_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertThatNoException().isThrownBy(() -> new TokenService(DEFAULT_SECRET, 60, DEFAULT_SECRET, env));
    }

    @Test
    void criterion5_productionProfile_customLegacySecrets_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatNoException().isThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, LEGACY_A + ", " + LEGACY_B, env));
    }

    // --- WO-SEC-63 (F20): fail-fast on SHORT secrets — the old gate only matched the default
    // literal, so a 6-char "secret" passed it silently. Length is the real measure of key
    // strength (32 chars = 256 bits = HS256 output), and it applies everywhere, not only
    // against known literals.

    @Test
    void criterion6_productionProfile_shortSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService("short", 60, "", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    @Test
    void criterion6_noProfile_shortSecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        assertThatThrownBy(() -> new TokenService("short", 60, "", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }

    @Test
    void criterion6_testProfile_shortSecret_doesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertThatNoException().isThrownBy(() -> new TokenService("short", 60, "", env));
    }

    @Test
    void criterion6_productionProfile_shortLegacySecret_throwsIllegalState() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        assertThatThrownBy(() -> new TokenService(CUSTOM_SECRET, 60, "short," + LEGACY_A, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-legacy-secrets");
    }
}
