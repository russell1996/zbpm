package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-please-override";
    private final TokenService tokens = new TokenService(SECRET, 60);

    @Test
    void issueThenVerifyReturnsClaims() {
        UUID userId = UUID.randomUUID();
        String token = tokens.issue(userId, "alice", "ADMIN");
        TokenService.Claims claims = tokens.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo("ADMIN");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue(UUID.randomUUID(), "alice", "USER");
        assertThat(tokens.verify(token + "x")).isNull();
        assertThat(tokens.verify("a.b.c")).isNull();
        assertThat(tokens.verify("garbage")).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new TokenService("a-different-secret", 60).issue(UUID.randomUUID(), "alice", "USER");
        assertThat(tokens.verify(token)).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService expired = new TokenService(SECRET, -1); // exp set in the past
        String token = expired.issue(UUID.randomUUID(), "alice", "USER");
        assertThat(expired.verify(token)).isNull();
    }
}
