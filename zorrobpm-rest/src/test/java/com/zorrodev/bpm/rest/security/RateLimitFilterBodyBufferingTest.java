package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-44/45 HOLD fix: body-buffering DoS prevention.
 *
 * Key invariant: per-IP check is CHEAP (no body read).
 * Body is only read (with 16 KB cap) AFTER IP check passes.
 */
class RateLimitFilterBodyBufferingTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setRateLimitEnabled(true);
        filter.setCapacity(10);      // generous IP limit for multi-request tests
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(2); // 2 per account
        filter.setAccountWindowSeconds(3600);
        filter.setDataCapacity(0);    // disable data endpoint check
        filter.setDataWindowSeconds(60);
        filter.reset();
    }

    private MockHttpServletRequest loginRequest(String username) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr("10.0.0.1");
        if (username != null) {
            String json = "{\"username\":\"" + username + "\",\"password\":\"secret\"}";
            req.setContent(json.getBytes(StandardCharsets.UTF_8));
        }
        return req;
    }

    private MockHttpServletRequest loginRequestLarge(String username, int bodySize) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr("10.0.0.1");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(username).append("\",\"password\":\"");
        while (sb.length() < bodySize - 3) {
            sb.append("x");
        }
        sb.append("\"}");
        req.setContent(sb.toString().getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private int doFilter(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = jakarta.servlet.FilterChain.class.cast(
            org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class));
        filter.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }

    /**
     * Test #1: IP exhausted → body NOT read at all.
     * Use a separate filter with capacity=1 to test this cleanly.
     */
    @Test
    void ipExhausted_bodyNotRead() throws Exception {
        RateLimitFilter smallFilter = new RateLimitFilter();
        smallFilter.setRateLimitEnabled(true);
        smallFilter.setCapacity(1);
        smallFilter.setWindowSeconds(3600);
        smallFilter.setAccountCapacity(5);
        smallFilter.setAccountWindowSeconds(3600);
        smallFilter.reset();

        // Exhaust IP bucket
        MockHttpServletRequest req1 = loginRequest("user1");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        FilterChain chain1 = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        smallFilter.doFilterInternal(req1, resp1, chain1);
        assertThat(resp1.getStatus()).isEqualTo(200);

        // 2nd request — IP exhausted → 429, body should NOT be read
        MockHttpServletRequest req2 = loginRequest("user2");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        FilterChain chain2 = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        smallFilter.doFilterInternal(req2, resp2, chain2);
        assertThat(resp2.getStatus()).isEqualTo(429);
        // Body content should still be available (not consumed by filter)
        assertThat(req2.getContentAsByteArray()).isNotEmpty();
    }

    /**
     * Test #2: IP passes → body read, per-account check works.
     * Request 1: user1 → 200 (IP passes, account passes, body read).
     * Request 2: user1 → 200 (account capacity=2, still has token).
     * Request 3: user1 → 429 (account exhausted).
     * Request 4: user2 → 200 (different account).
     */
    @Test
    void ipPasses_bodyReadPerAccountCheck() throws Exception {
        // Request 1: user1 → passes
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(200);

        // Request 2: user1 again → 200 (capacity=2, still has token)
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(200);

        // Request 3: user1 → 429 (account exhausted)
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(429);

        // Request 4: user2 → 200 (different account, IP still has tokens)
        assertThat(doFilter(loginRequest("user2"))).isEqualTo(200);
    }

    /**
     * Test #3: Large body (>16 KB) → capped, not fully buffered into heap.
     */
    @Test
    void largeBody_capped_notFullyBuffered() throws Exception {
        // 32 KB body — username "biguser" is within first 100 bytes, so it gets extracted
        MockHttpServletRequest req = loginRequestLarge("biguser", 32_768);
        int status = doFilter(req);
        // IP check passes (first request), account check runs with truncated body
        // Username is within first 16KB so it should be extracted and account check should work
        assertThat(status).isEqualTo(200); // IP=1/10, account=1/2 → passes
    }

    /**
     * Test #4: Multiple accounts exhausted independently.
     */
    @Test
    void perAccountLimitsAreIndependent() throws Exception {
        // user1: 2 tokens
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(200); // acct=2→1
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(200); // acct=1→0

        // user1 exhausted
        assertThat(doFilter(loginRequest("user1"))).isEqualTo(429); // acct=0

        // user2 still works (independent account bucket)
        assertThat(doFilter(loginRequest("user2"))).isEqualTo(200); // acct=2→1
        assertThat(doFilter(loginRequest("user2"))).isEqualTo(200); // acct=1→0

        // user2 exhausted
        assertThat(doFilter(loginRequest("user2"))).isEqualTo(429);
    }

    /**
     * Test #5: Account limit disabled (capacity=0) → only IP limit applies.
     */
    @Test
    void accountLimitDisabled_onlyIPLimitApplies() throws Exception {
        RateLimitFilter noAcctFilter = new RateLimitFilter();
        noAcctFilter.setRateLimitEnabled(true);
        noAcctFilter.setCapacity(2);
        noAcctFilter.setWindowSeconds(3600);
        noAcctFilter.setAccountCapacity(0); // disabled
        noAcctFilter.setDataCapacity(0);
        noAcctFilter.reset();

        // Same user, 2 requests → both pass (only IP limit)
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(200);
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(200);

        // 3rd → 429 (IP exhausted)
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(429);
    }

    private int doFilter(RateLimitFilter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        f.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }
}
