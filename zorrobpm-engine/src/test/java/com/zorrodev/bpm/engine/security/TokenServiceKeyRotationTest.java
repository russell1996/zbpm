package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-57: rotatable JWT signing keys.
 *  #1: issued token carries a deterministic kid in the header
 *  #2: token signed with key A verifies after rotation to active key B, while A is in the accepted set (POF)
 *  #3: token signed with a key outside the set is rejected
 *  #4: legacy token WITHOUT kid verifies with the ACTIVE key (deploy must not log everyone out)
 *  #5: alg != HS256 still rejected (WO-SEC-31b regression, unit level)
 *  #6: config with only jwt-secret (no legacy set) works exactly as before
 *  #7: refresh tokens are opaque — they survive key rotation
 */
class TokenServiceKeyRotationTest {

    private static final String KEY_A = "rotation-test-key-A-0123456789abcdef";
    private static final String KEY_B = "rotation-test-key-B-0123456789abcdef";
    private static final String KEY_C = "rotation-test-key-C-0123456789abcdef";

    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getUrlDecoder();
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    private TokenService serviceWith(String activeKey, String legacyCsv) {
        return new TokenService(activeKey, 60, legacyCsv, new MockEnvironment());
    }

    private Map<String, Object> validPayload() {
        return Map.of(
            "sub", UUID.randomUUID().toString(),
            "username", "testuser",
            "role", "USER",
            "exp", Instant.now().plusSeconds(3600).getEpochSecond()
        );
    }

    /** Builds a token by hand (input data, not the fix logic) — signed with the given key. */
    private String buildToken(String alg, String kidOrNull, Map<String, Object> payload, String signingKey) throws Exception {
        Map<String, Object> headerMap = kidOrNull == null
            ? Map.of("alg", alg, "typ", "JWT")
            : Map.of("alg", alg, "kid", kidOrNull, "typ", "JWT");
        String header = b64.encodeToString(mapper.writeValueAsBytes(headerMap));
        String payloadB64 = b64.encodeToString(mapper.writeValueAsBytes(payload));
        String signingInput = header + "." + payloadB64;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = b64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }

    // --- Criterion #1: issued token carries a deterministic kid in the header ---

    @Test
    void criterion1_issuedToken_carriesKidInHeader() throws Exception {
        String token = serviceWith(KEY_A, "").issue(UUID.randomUUID(), "alice", "USER", 0);
        Map<String, Object> header = mapper.readValue(b64d.decode(token.split("\\.")[0]), Map.class);
        String kid = (String) header.get("kid");
        assertThat(kid).isNotBlank();
        assertThat(header.get("alg")).isEqualTo("HS256");

        // kid must be deterministic per key: another service with the same key emits the same kid
        Map<String, Object> header2 = mapper.readValue(
            b64d.decode(serviceWith(KEY_A, "").issue(UUID.randomUUID(), "alice", "USER", 0).split("\\.")[0]), Map.class);
        assertThat(header2.get("kid")).isEqualTo(kid);
    }

    // --- Criterion #2 (POF): token signed with key A verifies after rotation to B, while A is accepted ---

    @Test
    void criterion2_tokenSignedWithKeyA_verifiesAfterRotationWhileAInAccepted() {
        UUID userId = UUID.randomUUID();
        String tokenBeforeRotation = serviceWith(KEY_A, "").issue(userId, "alice", "ADMIN", 0);

        // rotation: active key becomes B, A moves to the accepted (legacy) set
        TokenService rotated = serviceWith(KEY_B, KEY_A);
        TokenService.Claims claims = rotated.verify(tokenBeforeRotation);

        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo("ADMIN");
    }

    // --- Criterion #3: token with a kid outside the accepted set is rejected ---

    @Test
    void criterion3_tokenSignedWithUnknownKey_rejected() throws Exception {
        // Signed with the ACTIVE key B but carrying an unknown kid — without kid-lookup this
        // would pass (signature valid), so the test proves the lookup itself.
        String token = buildToken("HS256", "unknown-key-id-not-in-set", validPayload(), KEY_B);
        assertThat(serviceWith(KEY_B, KEY_A).verify(token)).isNull();
    }

    // --- Criterion #4: legacy token WITHOUT kid verifies with the ACTIVE key ---

    @Test
    void criterion4_tokenWithoutKid_verifiesWithActiveKey() throws Exception {
        // exactly the format issued before this change: no kid, signed with the then-active key,
        // which is now the active key of the verifying service
        String legacyToken = buildToken("HS256", null, validPayload(), KEY_B);
        TokenService.Claims claims = serviceWith(KEY_B, "").verify(legacyToken);
        assertThat(claims).isNotNull();
        assertThat(claims.username()).isEqualTo("testuser");
    }

    // --- Criterion #5: alg != HS256 still rejected (WO-SEC-31b regression) ---

    @Test
    void criterion5_algNone_rejected() throws Exception {
        String token = buildToken("none", null, validPayload(), KEY_B);
        assertThat(serviceWith(KEY_B, "").verify(token)).isNull();
    }

    @Test
    void criterion5_algRS256_rejected() throws Exception {
        String token = buildToken("RS256", null, validPayload(), KEY_B);
        assertThat(serviceWith(KEY_B, "").verify(token)).isNull();
    }

    @Test
    void criterion5_algNoneWithKid_rejected() throws Exception {
        // alg-confusion guard must apply to kid-carrying tokens as well. The kid MUST be a real
        // active key's kid (taken from a live issued token): with a made-up kid the token is
        // rejected by the kid lookup, not by the alg guard — the test would stay green even if
        // the alg check were removed, promising more than it verifies.
        String realKid = (String) mapper.readValue(
            b64d.decode(serviceWith(KEY_B, "").issue(UUID.randomUUID(), "alice", "USER", 0).split("\\.")[0]),
            Map.class).get("kid");
        String token = buildToken("none", realKid, validPayload(), KEY_B);
        assertThat(serviceWith(KEY_B, "").verify(token)).isNull();
    }

    // --- Criterion #6: config with only jwt-secret works as before ---

    @Test
    void criterion6_onlyJwtSecret_worksAsBefore() {
        TokenService singleKey = serviceWith(KEY_B, "");
        UUID userId = UUID.randomUUID();
        String token = singleKey.issue(userId, "bob", "USER", 0);
        TokenService.Claims claims = singleKey.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("bob");
        assertThat(claims.role()).isEqualTo("USER");
    }

    // --- Criterion #7: refresh tokens are opaque and survive rotation ---

    @Test
    void criterion7_refreshTokens_surviveRotation() {
        TokenService before = serviceWith(KEY_A, "");
        TokenService after = serviceWith(KEY_B, "");

        String refreshToken = before.generateRefreshToken();
        assertThat(refreshToken).isNotBlank();
        // not a JWT: opaque string without the three dot-separated parts
        assertThat(refreshToken.split("\\.")).hasSize(1);

        // the stored hash is deterministic and independent of the signing key
        assertThat(after.hashToken(refreshToken)).isEqualTo(before.hashToken(refreshToken));
    }
}