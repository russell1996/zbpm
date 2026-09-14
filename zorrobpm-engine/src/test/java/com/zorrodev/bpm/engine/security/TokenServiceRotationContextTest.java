package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-57, V11: the legacy-secrets property must reach the REAL bean through Spring wiring
 * (application-prod.yml / application.properties -> @Value), not a hand-wired unit stand.
 * G-C: application-prod.yml is a security config — its change requires a full-context test.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = {
    // active key stays the test default; the legacy CSV is configured the way prod would set it
    "zorrobpm.security.jwt-legacy-secrets=" + TokenServiceRotationContextTest.LEGACY_KEY
})
class TokenServiceRotationContextTest {

    static final String LEGACY_KEY = "context-legacy-key-0123456789abcdef";

    @Autowired TokenService tokenService;

    @Test
    void configuredLegacySecret_isAcceptedByRealBean() {
        // token issued by the OLD active key (now legacy) must still verify after rotation
        TokenService oldService = new TokenService(LEGACY_KEY, 60, "", new MockEnvironment());
        String oldToken = oldService.issue(UUID.randomUUID(), "alice", "ADMIN", 0);

        TokenService.Claims claims = tokenService.verify(oldToken);
        assertThat(claims).isNotNull();
        assertThat(claims.username()).isEqualTo("alice");
    }

    @Test
    void activeKeyStillIssuesAndVerifies() {
        UUID userId = UUID.randomUUID();
        String token = tokenService.issue(userId, "bob", "USER", 0);
        TokenService.Claims claims = tokenService.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("bob");
    }
}
