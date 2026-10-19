package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-10 / F-08: status table of the idempotency replay cache.
 *
 * <p>Cacheable (persisted and replayed): 2xx — the successful effect, and
 * the deterministic client errors 400 (bad body) / 413 (too big): retrying
 * with the SAME body produces the SAME result, so replaying is correct and
 * saves a re-validation. Everything else (401/403/404/409/422/429/5xx) is
 * NON-cacheable: the state may have changed (token expired, resource
 * created elsewhere, conflict resolved, transient backend fault) — the
 * retry must re-execute, see {@code Audit10Idempotency5xxTest} for the
 * 500 case end-to-end.
 */
class Audit10IdempotencyCacheGateTest {

    @ParameterizedTest(name = "status {0} → cacheable={1}")
    @CsvSource({
        // successful effects — always cached
        "200, true",
        "201, true",
        "204, true",
        // deterministic client errors — cached (same body → same result)
        "400, true",
        "413, true",
        // authz/rate — the token or the quota may have changed: re-execute
        "401, false",
        "403, false",
        "429, false",
        // the resource may now exist / be free: re-execute
        "404, false",
        "409, false",
        "422, false",
        // transient backend faults — the transaction rolled back: re-execute
        "500, false",
        "502, false",
        "503, false",
        "504, false",
    })
    void cacheableStatusTable(int status, boolean expected) {
        assertThat(IdempotencyFilter.isCacheableStatus(status))
            .as("status %d", status)
            .isEqualTo(expected);
    }

    @Test
    void cacheableStatus_isPackageVisibleGate() {
        // Regression guard: the gate must stay a plain static method on the
        // filter (the only place the decision is made), not a hidden branch.
        assertThat(IdempotencyFilter.isCacheableStatus(201)).isTrue();
        assertThat(IdempotencyFilter.isCacheableStatus(500)).isFalse();
    }
}
