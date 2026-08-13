package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-please-override";
    private static final String OTHER_SECRET = "a-different-secret";
    private final TokenService tokens = new TokenService(SECRET, 60, "", new MockEnvironment());
    private final Base64.Decoder b64d = Base64.getUrlDecoder();
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

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
        String token = new TokenService(OTHER_SECRET, 60, "", new MockEnvironment()).issue(UUID.randomUUID(), "alice", "USER");
        assertThat(tokens.verify(token)).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        TokenService expired = new TokenService(SECRET, -1, "", new MockEnvironment()); // exp set in the past
        String token = expired.issue(UUID.randomUUID(), "alice", "USER");
        assertThat(expired.verify(token)).isNull();
    }
}
