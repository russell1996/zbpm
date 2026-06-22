package com.zorrodev.bpm.engine.security;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal stateless bearer token (JWT-like, HS256) issued on login and verified on each request.
 * Self-contained: HMAC-SHA256 via the JDK, JSON via Jackson — no extra dependencies, no Keycloak.
 */
@Component
public class TokenService {

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getUrlDecoder();

    public TokenService(
        @Value("${zorrobpm.security.jwt-secret:change-me-dev-secret-please-override-in-production}") String secret,
        @Value("${zorrobpm.security.jwt-ttl-minutes:720}") long ttlMinutes) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlMinutes * 60;
    }

    public record Claims(UUID userId, String username, String role, long exp) {}

    /** Issues a signed token carrying the user id, username and role. */
    public String issue(UUID userId, String username, String role) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        try {
            String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            byte[] payloadJson = mapper.writeValueAsBytes(Map.of(
                "sub", userId.toString(), "username", username, "role", role, "exp", exp));
            String payload = b64.encodeToString(payloadJson);
            String signingInput = header + "." + payload;
            return signingInput + "." + b64.encodeToString(hmac(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue token", e);
        }
    }

    /** Verifies signature and expiry; returns the claims, or null if the token is invalid/expired. */
    @SuppressWarnings("unchecked")
    public Claims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String signingInput = parts[0] + "." + parts[1];
            if (!constantTimeEquals(b64.encodeToString(hmac(signingInput)), parts[2])) return null;
            Map<String, Object> payload = mapper.readValue(b64d.decode(parts[1]), Map.class);
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) return null;
            return new Claims(
                UUID.fromString((String) payload.get("sub")),
                (String) payload.get("username"),
                (String) payload.get("role"),
                exp);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
