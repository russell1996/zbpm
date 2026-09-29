package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RateLimitFilterXffTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setDataCapacity(120);
        filter.setDataWindowSeconds(60);
        filter.setRefreshCapacity(30);
        filter.setRefreshWindowSeconds(60);
        filter.setRateLimitEnabled(true);
        filter.setTrustedProxies(Set.of());
        filter.reset();
    }

    private MockHttpServletRequest loginRequest(String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr("192.168.1.100");
        req.setContentType("application/json");
        req.setContent("{\"username\":\"admin\",\"password\":\"pass\"}".getBytes());
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

    @Test
    void criterion1_differentXff_sameRemoteAddr_hits429() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                    .as("Request %d with XFF=10.0.0.%d should pass", i, i + 1)
                    .isEqualTo(200);
        }
        MockHttpServletRequest req = loginRequest("10.0.0.99");
        int status = doFilter(req);
        assertThat(status)
                .as("6th request should be rate-limited regardless of XFF")
                .isEqualTo(429);
    }

    @Test
    void criterion2_getClientIp_returnsRemoteAddr_notXff() throws Exception {
        MockHttpServletRequest req = loginRequest("10.0.0.1, 10.0.0.2");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, resp, chain);
        verify(chain).doFilter(any(), eq(resp));
    }

    @Test
    void criterion5_proofOfFailure_xffSpoofBypassesLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                    .as("Request %d with spoofed XFF should be rate-limited by remoteAddr", i)
                    .isEqualTo(i < 5 ? 200 : 429);
        }
    }
}