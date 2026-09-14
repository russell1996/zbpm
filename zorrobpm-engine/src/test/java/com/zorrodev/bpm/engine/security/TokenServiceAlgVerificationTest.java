package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-31b: V11 test — JWT alg verification with real Spring context.
 * Tokens with alg=none or alg=RS256 must be rejected.
 */
@ActiveProfiles("test")
@SpringBootTest
class TokenServiceAlgVerificationTest {

    @Autowired TokenService tokenService;

    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getUrlDecoder();
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    private String buildToken(String alg, Map<String, Object> payload) throws Exception {
        String header = b64.encodeToString(mapper.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT")));
        byte[] payloadJson = mapper.writeValueAsBytes(payload);
        String payloadB64 = b64.encodeToString(payloadJson);
        String signingInput = header + "." + payloadB64;
        // For non-HS256 alg, signature is arbitrary (won't match HMAC)
        String signature = b64.encodeToString(("fake-sig-" + alg).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + signature;
    }

    private Map<String, Object> validPayload() {
        return Map.of(
            "sub", UUID.randomUUID().toString(),
            "username", "testuser",
            "role", "USER",
            "exp", Instant.now().plusSeconds(3600).getEpochSecond()
        );
    }

    // --- POF: alg=none must be rejected ---

    @Test
    void verify_tokenWithAlgNone_rejected() throws Exception {
        String token = buildToken("none", validPayload());
        TokenService.Claims claims = tokenService.verify(token);
        assertThat(claims).isNull();
    }

    // --- POF: alg=RS256 must be rejected ---

    @Test
    void verify_tokenWithAlgRS256_rejected() throws Exception {
        String token = buildToken("RS256", validPayload());
        TokenService.Claims claims = tokenService.verify(token);
        assertThat(claims).isNull();
    }

    // --- Valid HS256 token still works ---

    @Test
    void verify_validHS256Token_accepted() {
        UUID userId = UUID.randomUUID();
        String token = tokenService.issue(userId, "alice", "ADMIN", 0);
        TokenService.Claims claims = tokenService.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo("ADMIN");
    }
}
