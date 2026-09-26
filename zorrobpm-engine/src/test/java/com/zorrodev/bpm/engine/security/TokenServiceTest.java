package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-please-override";
    // >= MIN_SECRET_LENGTH (WO-SEC-63 F20): must be a valid random-length secret, not a short fixture
    private static final String OTHER_SECRET = "a-different-secret-0123456789abcdef";
    private final TokenService tokens = new TokenService(SECRET, 60, "", new MockEnvironment());

    @Test
    void issueThenVerifyReturnsClaims() {
        UUID userId = UUID.randomUUID();
        String token = tokens.issue(userId, "alice", "ADMIN", 0);
        TokenService.Claims claims = tokens.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo("ADMIN");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue(UUID.randomUUID(), "alice", "USER", 0);
        assertThat(tokens.verify(token + "x")).isNull();
        assertThat(tokens.verify("a.b.c")).isNull();
        assertThat(tokens.verify("garbage")).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new TokenService(OTHER_SECRET, 60, "", new MockEnvironment()).issue(UUID.randomUUID(), "alice", "USER", 0);
        assertThat(tokens.verify(token)).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService expired = new TokenService(SECRET, -1, "", new MockEnvironment()); // exp set in the past
        String token = expired.issue(UUID.randomUUID(), "alice", "USER", 0);
        assertThat(expired.verify(token)).isNull();
    }

    // --- WO-SEC-63: the "ver" (tokenVersion) claim must round-trip through issue/verify and
    // default to 0 for legacy tokens that carry no such claim.

    @Test
    void issueWithTokenVersion_verifyReturnsSameVersion() {
        UUID userId = UUID.randomUUID();
        String token = tokens.issue(userId, "alice", "USER", 42);
        TokenService.Claims claims = tokens.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.tokenVersion()).isEqualTo(42);
    }

    @Test
    void verify_legacyTokenWithoutVersionClaim_returnsDefaultZero() {
        // Hand-built HS256 token WITHOUT the "ver" claim: exactly the format TokenService
        // issued before WO-SEC-63. Verify() must treat the missing claim as version 0 so
        // pre-upgrade sessions keep working until the first logout/password change.
        // The token is FABRICATED here (input side) — the logic under test is verify()'s
        // defaulting; removing that default makes verify() return null and this test goes RED.
        String legacy = legacyTokenWithoutVersionClaim(UUID.randomUUID(), "alice", "USER");
        TokenService.Claims claims = tokens.verify(legacy);
        assertThat(claims).isNotNull();
        assertThat(claims.tokenVersion()).isZero();
    }

    /**
     * Produces a token in the pre-WO-SEC-63 shape: same header/payload/HS256 as issue(),
     * but WITHOUT the {@code ver} claim (and without {@code kid} — the pre-rotation
     * format, which verify() resolves against the active key). The MAC is what the
     * production code would have computed with this secret; only the payload content
     * differs — that is the input a legacy token is, not the logic under test.
     */
    private String legacyTokenWithoutVersionClaim(UUID userId, String username, String role) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long exp = Instant.now().getEpochSecond() + 3600;
            String payloadJson = "{\"sub\":\"" + userId + "\",\"username\":\"" + username
                + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}";
            String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fabricate legacy token", e);
        }
    }
}
