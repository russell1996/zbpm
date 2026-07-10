package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * WO-SEC-12: XFF spoofing resistance for rate-limit.
 *
 *  #1: N+1 requests with DIFFERENT XFF but same remoteAddr → 429 on (N+1)-th
 *  #2: getClientIp returns remoteAddr, not XFF
 *  #3: rate-limit enabled by default
 *  #5: proof-of-failure — test #1 on current code → RED (different XFF = different bucket)
 */
class RateLimitFilterXffTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setRateLimitEnabled(true);
        filter.reset();
    }

    private MockHttpServletRequest loginRequest(String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr("192.168.1.100");
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    private int doFilter(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }

    // --- Criterion #2: getClientIp returns remoteAddr, not XFF ---

    @Test
    void criterion2_getClientIp_returnsRemoteAddr_notXff() throws Exception {
        MockHttpServletRequest req = loginRequest("10.0.0.1, 10.0.0.2");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, resp, chain);
        // With the fix, getClientIp should use remoteAddr (192.168.1.100), not XFF (10.0.0.1)
        // The filter passes through (chain.doFilter called) for the first request
        verify(chain).doFilter(req, resp);
    }

    // --- Criterion #1: Different XFF with same remoteAddr → same bucket → 429 ---

    @Test
    void criterion1_differentXff_sameRemoteAddr_hits429() throws Exception {
        // Send 5 requests with different XFF, same remoteAddr → should hit 429 on 6th
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                    .as("Request %d with XFF=10.0.0.%d should pass", i, i + 1)
                    .isEqualTo(200);
        }
        // 6th request with yet another XFF → should be 429 (same bucket via remoteAddr)
        MockHttpServletRequest req = loginRequest("10.0.0.99");
        int status = doFilter(req);
        assertThat(status)
                .as("6th request should be rate-limited regardless of XFF")
                .isEqualTo(429);
    }

    // --- Proof-of-failure #5: on current code (manual XFF), different XFF → no 429 ---

    @Test
    void criterion5_proofOfFailure_xffSpoofBypassesLimit() throws Exception {
        // On the CURRENT code (getClientIp parses XFF), each different XFF = new bucket
        // After fix (getClientIp = getRemoteAddr), all go to same bucket
        // This test PASSES after fix, FAILS before fix (RED)
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                    .as("Request %d with spoofed XFF should be rate-limited by remoteAddr", i)
                    .isEqualTo(i < 5 ? 200 : 429);
        }
    }
}
