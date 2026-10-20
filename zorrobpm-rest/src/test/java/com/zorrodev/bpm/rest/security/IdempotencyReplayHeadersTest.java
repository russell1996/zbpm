package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-14 (F-16): the headers persisted for an idempotent replay.
 *
 * <p>Before the fix the replay restored only status/body/contentType — the
 * {@code Location} of the created resource was lost, so a client that retried
 * with the same {@code Idempotency-Key} got the same id in the body but no
 * {@code Location} header: the two answers were NOT the same answer.
 *
 * <p>What is persisted is an ALLOWLIST ({@code Location}, {@code ETag}) and
 * nothing else: {@code Set-Cookie} must never be stored (a replayed session
 * cookie is a credential leak), and a stored blob that cannot be parsed must
 * not break the replay (fail-soft).
 */
class IdempotencyReplayHeadersTest {

    @Test
    void collectReplayableHeaders_keepsLocationAndEtag_only() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Location", "/process-instances/11111111-1111-1111-1111-111111111111");
        response.setHeader("ETag", "\"v7\"");
        response.setHeader("Set-Cookie", "zbpm_token=secret-session-token");
        response.setHeader("X-Internal-Debug", "leak-me-not");

        Map<String, String> collected = IdempotencyFilter.collectReplayableHeaders(response);

        assertThat(collected)
            .as("only the allowlisted headers may be persisted")
            .containsOnlyKeys("Location", "ETag");
        assertThat(collected.get("Location")).isEqualTo("/process-instances/11111111-1111-1111-1111-111111111111");
        assertThat(collected.get("ETag")).isEqualTo("\"v7\"");
        assertThat(collected.toString())
            .as("a session cookie must never reach the idempotency table")
            .doesNotContain("secret-session-token");
    }

    @Test
    void collectReplayableHeaders_withoutAnyHeader_isEmpty() {
        assertThat(IdempotencyFilter.collectReplayableHeaders(new MockHttpServletResponse()))
            .isEmpty();
    }

    @Test
    void serializeReplayableHeaders_emptyMap_isNull() {
        assertThat(IdempotencyFilter.serializeReplayableHeaders(Map.of()))
            .as("nothing to persist -> no column value (legacy rows stay null)")
            .isNull();
    }

    @Test
    void serializeReplayableHeaders_roundTripsThroughParse() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Location", "/process-instances/22222222-2222-2222-2222-222222222222");
        headers.put("ETag", "\"v3\"");

        String json = IdempotencyFilter.serializeReplayableHeaders(headers);

        assertThat(json).isNotNull();
        assertThat(IdempotencyFilter.parseReplayableHeaders(json))
            .containsExactlyInAnyOrderEntriesOf(headers);
    }

    @Test
    void parseReplayableHeaders_unreadableJson_failsSoftToEmpty() {
        assertThat(IdempotencyFilter.parseReplayableHeaders("{not json at all"))
            .as("a corrupted column value must not break the replay")
            .isEmpty();
    }

    @Test
    void parseReplayableHeaders_nullOrBlank_isEmpty() {
        assertThat(IdempotencyFilter.parseReplayableHeaders(null)).isEmpty();
        assertThat(IdempotencyFilter.parseReplayableHeaders("   ")).isEmpty();
    }

    @Test
    void parseReplayableHeaders_dropsForeignKeysEvenIfStored() {
        // Defence in depth: the allowlist is applied on READ as well, so a row
        // written by another version (or by hand) cannot inject arbitrary headers.
        String json = "{\"Location\":\"/x\",\"Set-Cookie\":\"a=b\",\"X-Evil\":\"1\"}";

        Map<String, String> parsed = IdempotencyFilter.parseReplayableHeaders(json);

        assertThat(parsed).containsOnlyKeys("Location");
        assertThat(parsed.toString()).doesNotContain("Set-Cookie");
    }
}
