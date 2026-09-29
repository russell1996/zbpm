package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-44/45 HOLD fix: body-buffering DoS prevention.
 *
 * Key invariant: per-IP check is CHEAP (no body read).
 * Body is only read AFTER IP check passes, and oversized declared bodies
 * are rejected with 413 BEFORE any byte is buffered.
 */
class RateLimitFilterBodyBufferingTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setRateLimitEnabled(true);
        filter.setCapacity(10);      // generous IP limit for multi-request tests
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(2); // 2 per account
        filter.setRefreshCapacity(3);
        filter.setRefreshWindowSeconds(3600);
        filter.setDataCapacity(10);
        filter.setDataWindowSeconds(3600);
        filter.setTrustedProxies(Set.of());
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

    private int doFilter(RateLimitFilter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        f.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }

    /**
     * Test #1: IP exhausted → body NOT read at all.
     * Use a separate filter with capacity=1 to test this cleanly.
     */
    @Test
    void ipExhausted_bodyNotRead() throws Exception {
        RateLimitFilter smallFilter = new RateLimitFilter();
        smallFilter.setPgRateLimiter(TestRateLimitBuckets.create());
        smallFilter.setRateLimitEnabled(true);
        smallFilter.setCapacity(1);
        smallFilter.setWindowSeconds(3600);
        smallFilter.setAccountCapacity(5);
        smallFilter.setRefreshCapacity(3);
        smallFilter.setRefreshWindowSeconds(3600);
        smallFilter.setDataCapacity(10);
        smallFilter.setDataWindowSeconds(3600);
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
     * Request 2: user1 → 200 (capacity=2, still has token).
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
     * Test #3: Declared body larger than 16 KB cap → 413 BEFORE buffering.
     * No bytes are read into memory — rejected by Content-Length alone.
     */
    @Test
    void largeBody_declaredOverCap_returns413() throws Exception {
        MockHttpServletRequest req = loginRequestLarge("biguser", 32_768);
        int status = doFilter(req);
        assertThat(status).isEqualTo(413);

        // IP token was consumed for this attempt (IP check ran before size check),
        // but no body was buffered — verify a follow-up small request still works
        // with a different account (IP capacity=10, generous).
        assertThat(doFilter(loginRequest("normaluser"))).isEqualTo(200);
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
        noAcctFilter.setPgRateLimiter(TestRateLimitBuckets.create());
        noAcctFilter.setRateLimitEnabled(true);
        noAcctFilter.setCapacity(2);
        noAcctFilter.setWindowSeconds(3600);
        noAcctFilter.setAccountCapacity(0); // disabled
        noAcctFilter.setRefreshCapacity(3);
        noAcctFilter.setRefreshWindowSeconds(3600);
        noAcctFilter.setDataCapacity(10);
        noAcctFilter.setDataWindowSeconds(3600);
        noAcctFilter.reset();

        // Same user, 2 requests → both pass (only IP limit)
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(200);
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(200);

        // 3rd → 429 (IP exhausted)
        assertThat(doFilter(noAcctFilter, loginRequest("sameuser"))).isEqualTo(429);
    }

    /**
     * Test #7: chunked request without Content-Length with a body > cap → 413,
     * buffered bytes stay bounded (no full-body allocation, no desync).
     */
    @Test
    void chunkedLargeBody_noContentLength_returns413() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr("10.0.0.1");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"chunky\",\"password\":\"");
        while (sb.length() < 32_768) {
            sb.append("x");
        }
        sb.append("\"}");
        req.setContent(sb.toString().getBytes(StandardCharsets.UTF_8));
        // Simulate chunked transfer: no Content-Length visible to the filter.
        jakarta.servlet.http.HttpServletRequest chunked = new jakarta.servlet.http.HttpServletRequestWrapper(req) {
            @Override
            public long getContentLengthLong() { return -1L; }
            @Override
            public int getContentLength() { return -1; }
        };
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        filter.doFilterInternal(chunked, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(413);
    }

    /**
     * Test #6: Account-limit rejection rolls back the IP token.
     * IP capacity=3, account capacity=2:
     *   req1 user1 → 200 (IP 3→2, acct 2→1)
     *   req2 user1 → 200 (IP 2→1, acct 1→0)
     *   req3 user1 → 429 (acct exhausted; IP 1→0, rollback → 1)
     *   req4 user2 → 200 (IP 1→0). WITHOUT rollback req4 would be 429 (IP 0).
     */
    @Test
    void accountRejection_rollsBackIpToken() throws Exception {
        RateLimitFilter tightFilter = new RateLimitFilter();
        tightFilter.setPgRateLimiter(TestRateLimitBuckets.create());
        tightFilter.setRateLimitEnabled(true);
        tightFilter.setCapacity(3);
        tightFilter.setWindowSeconds(3600);
        tightFilter.setAccountCapacity(2);
        tightFilter.setRefreshCapacity(3);
        tightFilter.setRefreshWindowSeconds(3600);
        tightFilter.setDataCapacity(10);
        tightFilter.setDataWindowSeconds(3600);
        tightFilter.reset();

        // Consume user1's 2 account tokens (2 IP tokens consumed too)
        assertThat(doFilter(tightFilter, loginRequest("user1"))).isEqualTo(200);
        assertThat(doFilter(tightFilter, loginRequest("user1"))).isEqualTo(200);

        // user1 exhausted: 3rd request → 429, IP token rolled back
        assertThat(doFilter(tightFilter, loginRequest("user1"))).isEqualTo(429);

        // user2 from same IP should still pass (rollback returned the IP token)
        assertThat(doFilter(tightFilter, loginRequest("user2"))).isEqualTo(200);
    }
}
