package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitFilterAccountTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setRefreshCapacity(3);
        filter.setRefreshWindowSeconds(3600);
        filter.setDataCapacity(120);
        filter.setDataWindowSeconds(60);
        filter.setRateLimitEnabled(true);
        filter.setTrustedProxies(Set.of());
        filter.reset();
    }

    private MockHttpServletRequest loginRequest(String ip, String username) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr(ip);
        req.setContentType("application/json");
        req.setContent(("{\"username\":\"%s\",\"password\":\"pass\"}").formatted(username).getBytes());
        return req;
    }

    private MockHttpServletRequest refreshRequest(String ip, String refreshToken) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/refresh");
        req.setRemoteAddr(ip);
        if (refreshToken != null) {
            req.setCookies(new jakarta.servlet.http.Cookie("refresh_token", refreshToken));
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
    void crit1_sameUsername_differentIps_hits429() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0." + (i + 1), "alice");
            int status = doFilter(req);
            assertThat(status)
                .as("Request %d from different IP should pass", i)
                .isEqualTo(200);
        }
        MockHttpServletRequest req = loginRequest("10.0.0.99", "alice");
        int status = doFilter(req);
        assertThat(status)
            .as("6th login for alice from new IP -> 429 (per-account limit)")
            .isEqualTo(429);
    }

    @Test
    void crit2_differentUsers_sameIp_ipBucketShared() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1", "user" + i);
            int status = doFilter(req);
            assertThat(status)
                .as("Request %d from user%d should pass", i, i)
                .isEqualTo(200);
        }
        MockHttpServletRequest req = loginRequest("10.0.0.1", "user5");
        int status = doFilter(req);
        assertThat(status)
            .as("6th request from same IP -> 429 (IP bucket shared)")
            .isEqualTo(429);
    }

    @Test
    void crit3_untrustedXff_ignored() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1", "bob");
            req.addHeader("X-Forwarded-For", "1.2.3." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                .as("Request %d should pass", i)
                .isEqualTo(200);
        }
        MockHttpServletRequest req = loginRequest("10.0.0.1", "bob");
        req.addHeader("X-Forwarded-For", "99.99.99.99");
        int status = doFilter(req);
        assertThat(status)
            .as("6th request with spoofed XFF -> 429 (XFF ignored)")
            .isEqualTo(429);
    }

    @Test
    void crit4_trustedProxy_extractsRealIp() throws Exception {
        filter.setTrustedProxies(Set.of("10.0.0.1"));
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1", "charlie");
            req.addHeader("X-Forwarded-For", "192.168.1." + (i + 1));
            int status = doFilter(req);
            assertThat(status)
                .as("Request %d from real IP 192.168.1.%d should pass", i, i + 1)
                .isEqualTo(200);
        }
        MockHttpServletRequest req6 = loginRequest("10.0.0.1", "charlie");
        req6.addHeader("X-Forwarded-For", "192.168.1.99");
        int status6 = doFilter(req6);
        assertThat(status6)
            .as("6th login for charlie -> 429 (per-account OR shared-IP limit)")
            .isEqualTo(429);
        // WO-SEC-52 / SEC-4: the LEFTMOST X-Forwarded-For entry is NO LONGER trusted to
        // separate clients behind a trusted proxy. Without nginx `real_ip_header` rewriting
        // the connection remote address, every client behind this proxy shares the SAME
        // bucket keyed on the proxy address (10.0.0.1). That is the secure choice — a client
        // cannot dodge the limit by forging XFF. The cost: to get per-client buckets you MUST
        // deploy nginx `real_ip_header X-Forwarded-For` + `set_real_ip_from` (see WO-SEC-52).
        MockHttpServletRequest reqOther = loginRequest("10.0.0.1", "eve");
        reqOther.addHeader("X-Forwarded-For", "192.168.1.50");
        int statusOther = doFilter(reqOther);
        assertThat(statusOther)
            .as("Different user behind the SAME proxy shares the proxy bucket (SEC-4) -> 429")
            .isEqualTo(429);
    }

    @Test
    void crit5_refreshRateLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = refreshRequest("10.0.0." + (i + 1), "token-abc123");
            int status = doFilter(req);
            assertThat(status)
                .as("Refresh request %d should pass", i)
                .isEqualTo(200);
        }
        MockHttpServletRequest req = refreshRequest("10.0.0.4", "token-abc123");
        int status = doFilter(req);
        assertThat(status)
            .as("4th refresh -> 429")
            .isEqualTo(429);
    }

    @Test
    void crit6_differentRefreshTokens_differentBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = refreshRequest("10.0.0." + (i + 1), "token-user" + i);
            int status = doFilter(req);
            assertThat(status)
                .as("Refresh for different token %d should pass", i)
                .isEqualTo(200);
        }
        MockHttpServletRequest req = refreshRequest("10.0.0.4", "token-another");
        int status = doFilter(req);
        assertThat(status)
            .as("Different token should pass")
            .isEqualTo(200);
    }

    @Test
    void crit7_trustedProxyCidr() throws Exception {
        filter.setTrustedProxies(Set.of("10.0.0.0/24"));
        MockHttpServletRequest req = loginRequest("10.0.0.5", "dave");
        req.addHeader("X-Forwarded-For", "172.16.0.1");
        int status = doFilter(req);
        assertThat(status).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req2 = loginRequest("10.0.0.5", "dave");
            req2.addHeader("X-Forwarded-For", "172.16.0.1");
            doFilter(req2);
        }
        MockHttpServletRequest req6 = loginRequest("10.0.0.5", "dave");
        req6.addHeader("X-Forwarded-For", "172.16.0.1");
        int status6 = doFilter(req6);
        assertThat(status6)
            .as("6th login for dave -> 429 (per-account limit)")
            .isEqualTo(429);
    }
}