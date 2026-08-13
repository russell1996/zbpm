package com.zorrodev.bpm.engine.security;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal stateless bearer token (JWT-like, HS256) issued on login and verified on each request.
 * Self-contained: HMAC-SHA256 via the JDK, JSON via Jackson — no extra dependencies, no Keycloak.
 *
 * WO-SEC-57: rotatable signing keys. One active key signs and verifies; an optional set of
 * legacy keys (zorrobpm.security.jwt-legacy-secrets, CSV) is accepted for VERIFICATION ONLY,
 * so a rotation does not invalidate tokens signed with the previous key. The token header carries
 * a deterministic key id (kid = b64url(SHA-256(secret))[:16]) derived from the key itself — no
 * separate id configuration to desync. Tokens WITHOUT kid (issued before this change) are verified
 * with the active key, so a deploy does not log everyone out.
 */
@Slf4j
@Component
public class TokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] activeSecret;
    private final String activeKeyId;
    private final Map<String, byte[]> acceptedKeys; // kid -> secret (active + legacy)
    private final long ttlSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getUrlDecoder();

    private static final String DEFAULT_SECRET = "change-me-dev-secret-please-override-in-production";
    private static final Set<String> SECRET_OPTIONAL_PROFILES = Set.of("dev", "test");

    public TokenService(
        @Value("${zorrobpm.security.jwt-secret:" + DEFAULT_SECRET + "}") String secret,
        @Value("${zorrobpm.security.jwt-ttl-minutes:30}") long ttlMinutes,
        @Value("${zorrobpm.security.jwt-legacy-secrets:}") String legacySecrets,
        Environment environment) {
        boolean devOrTest = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(SECRET_OPTIONAL_PROFILES::contains);
        if (!devOrTest && DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.jwt-secret must be set (default secret allowed only in dev/test profiles). "
                + "Set ZORROBPM_JWT_SECRET or application-prod.yml.");
        }
        this.activeSecret = secret.getBytes(StandardCharsets.UTF_8);
        this.activeKeyId = keyId(this.activeSecret);
        this.acceptedKeys = new LinkedHashMap<>();
        this.acceptedKeys.put(activeKeyId, activeSecret);
        if (legacySecrets != null && !legacySecrets.isBlank()) {
            for (String legacy : legacySecrets.split(",")) {
                String trimmed = legacy.trim();
                if (!trimmed.isEmpty()) {
                    byte[] legacyBytes = trimmed.getBytes(StandardCharsets.UTF_8);
                    this.acceptedKeys.putIfAbsent(keyId(legacyBytes), legacyBytes);
                }
            }
        }
        this.ttlSeconds = ttlMinutes * 60;
    }

    /**
     * Deterministic key id: b64url(SHA-256(secret)) truncated to 16 bytes. Same secret -> same id,
     * so rotation config never needs to carry explicit ids.
     */
    private static String keyId(byte[] secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 16));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive JWT key id", e);
        }
    }

    public record Claims(UUID userId, String username, String role, long exp) {}

    /** Issues a signed token carrying the user id, username and role. */
    public String issue(UUID userId, String username, String role) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        try {
            String header = b64.encodeToString(
                ("{\"alg\":\"HS256\",\"kid\":\"" + activeKeyId + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            byte[] payloadJson = mapper.writeValueAsBytes(Map.of(
                "sub", userId.toString(), "username", username, "role", role, "exp", exp));
            String payload = b64.encodeToString(payloadJson);
            String signingInput = header + "." + payload;
            return signingInput + "." + b64.encodeToString(hmac(signingInput, activeSecret));
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

            // WO-SEC-31b: reject tokens with alg != HS256 (alg-confusion attack prevention)
            Map<String, Object> header = mapper.readValue(b64d.decode(parts[0]), Map.class);
            String alg = (String) header.get("alg");
            if (!"HS256".equals(alg)) {
                log.debug("Rejected token with unsupported alg: {}", alg);
                return null;
            }

            String kid = (String) header.get("kid");
            byte[] key;
            if (kid == null) {
                // WO-SEC-57: legacy token issued before kid existed — verify with the ACTIVE key,
                // so a rotation deploy does not invalidate every live session at once.
                key = activeSecret;
            } else {
                key = acceptedKeys.get(kid);
                if (key == null) {
                    log.debug("Rejected token with unknown key id: {}", kid);
                    return null;
                }
            }

            String signingInput = parts[0] + "." + parts[1];
            if (!constantTimeEquals(b64.encodeToString(hmac(signingInput, key)), parts[2])) return null;
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

    private byte[] hmac(String data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        // WO-SEC-30a: constant-time comparison with no early return by length.
        // Accumulate XOR of length difference + all byte differences.
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        int diff = aBytes.length ^ bBytes.length;
        int len = Math.min(aBytes.length, bBytes.length);
        for (int i = 0; i < len; i++) diff |= aBytes[i] ^ bBytes[i];
        return diff == 0;
    }

    /** Generates a cryptographically random refresh token (not JWT — revocable). */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return b64.encodeToString(bytes);
    }

    /** Deterministic SHA-256 hash for refresh token storage/lookup. */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return b64.encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }
}
