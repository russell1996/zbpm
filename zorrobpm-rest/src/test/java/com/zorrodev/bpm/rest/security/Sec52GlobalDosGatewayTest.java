package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-SEC-52: global DoS backstop — nginx limit_req + extended app-level data buckets.
 *
 * <p>Meeting the CTO HOLD (round review) requirements:
 * <ul>
 *   <li>Crit-3 is exercised on the REAL prod default (dataCapacity=300 / dataWindowSeconds=60),
 *       not a tautological capacity=5 — proves normal cursor pagination does not trip a false 429.</li>
 *   <li>Crit-1 proves the data bucket actually triggers 429 when the real prod default is exceeded.</li>
 *   <li>SEC-4 POF: with a trusted proxy in front, spoofing the LEFTMOST X-Forwarded-For entry must NOT
 *       let an attacker dodge the shared bucket. The bucket key is the connection remote address
 *       (rewritten by nginx real_ip), never the attacker-controlled leftmost XFF.</li>
 * </ul>
 */
class Sec52GlobalDosGatewayTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setRateLimitEnabled(true);
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setRefreshCapacity(30);
        filter.setRefreshWindowSeconds(60);
        filter.setDataCapacity(300);
        filter.setDataWindowSeconds(60);
        filter.setTrustedProxies(Set.of());
        filter.reset();
    }

    private MockHttpServletRequest req(String method, String ip, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRemoteAddr(ip);
        return r;
    }

    private int status(MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(r, resp, chain);
        return resp.getStatus();
    }

    // --- Crit-3: normal cursor pagination under PROD DEFAULT (300/60s) — no false 429 ---

    @Test
    void crit3_normalEventsPagination_noFalse429_onProdDefault() throws Exception {
        // Prod defaults (WO-DIFF-6): 300 requests / 60s window. A normal UI poll/scan issues far fewer.
        for (int i = 0; i < 20; i++) {
            int s = status(req("GET", "10.0.0.7", "/events?cursor=abc"));
            assertThat(s)
                .as("Normal pagination request %d must pass on prod default 300/60s", i)
                .isEqualTo(200);
        }
    }

    // --- Crit-1: flooding a protected data path eventually returns 429 (limit really fires) ---

    @Test
    void crit1_floodEvents_exceedsProdDefault_returns429() throws Exception {
        for (int i = 0; i < 300; i++) {
            int s = status(req("GET", "10.0.0.8", "/events?cursor=x"));
            assertThat(s)
                .as("Within prod default capacity request %d should pass", i)
                .isEqualTo(200);
        }
        // 301st in the same window -> 429
        int s = status(req("GET", "10.0.0.8", "/events?cursor=y"));
        assertThat(s)
            .as("Exceeding prod default 300/60s must return 429")
            .isEqualTo(429);
    }

    // --- WO extension: /process-definitions is covered by the data bucket ---

    @Test
    void processDefinitions_isRateLimited() throws Exception {
        for (int i = 0; i < 300; i++) {
            assertThat(status(req("GET", "10.0.0.9", "/process-definitions")))
                .isEqualTo(200);
        }
        assertThat(status(req("GET", "10.0.0.9", "/process-definitions")))
            .isEqualTo(429);
    }

    // --- SEC-4 POF: trusted proxy + spoofed leftmost XFF must NOT create a separate bucket ---

    @Test
    void sec4_spoofedLeftmostXff_doesNotDodgeSharedBucket() throws Exception {
        // Simulate nginx WITHOUT real_ip: the app sees the proxy's address as remoteAddr,
        // and the proxy is declared trusted. An attacker prepends a forged leftmost XFF.
        filter.setTrustedProxies(Set.of("10.99.0.1"));
        filter.setDataCapacity(5);
        filter.setDataWindowSeconds(3600);

        // Exhaust the bucket (5 tokens) using one forged XFF value.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest r = req("GET", "10.99.0.1", "/events");
            r.addHeader("X-Forwarded-For", "203.0.113.9"); // attacker-chosen leftmost
            assertThat(status(r)).isEqualTo(200);
        }
        // 6th with the SAME forged XFF -> 429 (bucket exhausted)
        MockHttpServletRequest sameXff = req("GET", "10.99.0.1", "/events");
        sameXff.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(status(sameXff)).isEqualTo(429);

        // A DIFFERENT forged leftmost XFF from the same proxy address.
        // OLD behaviour: keyed on leftmost XFF -> fresh bucket -> 200 (attacker dodges the limit).
        // NEW behaviour: keyed on remoteAddr (proxy) -> same exhausted bucket -> 429.
        MockHttpServletRequest otherXff = req("GET", "10.99.0.1", "/events");
        otherXff.addHeader("X-Forwarded-For", "198.51.100.23");
        assertThat(status(otherXff))
            .as("Different spoofed leftmost XFF must NOT open a fresh bucket (SEC-4)")
            .isEqualTo(429);
    }
}
