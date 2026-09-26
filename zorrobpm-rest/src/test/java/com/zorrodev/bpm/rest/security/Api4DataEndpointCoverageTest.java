package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-API-4 (Finding #2, High): every audit-listed admin/aux path is gated by
 * the data bucket at filter level. Direct-filter unit (same rig as
 * {@code RateLimitFilterDataEndpointTest}): 10 requests pass, the 11th is 429.
 * The full-chain proof (shared bucket, real 429 body) lives in
 * {@code Api4AdminRateLimitIT}; this class pins each prefix individually so a
 * dropped prefix fails by name.
 */
class Api4DataEndpointCoverageTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setRefreshCapacity(30);
        filter.setRefreshWindowSeconds(60);
        filter.setDataCapacity(10);
        filter.setDataWindowSeconds(3600);
        filter.setRateLimitEnabled(true);
        filter.setTrustedProxies(Set.of());
        filter.reset();
    }

    private int doFilter(String method, String ip, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(req, resp, chain);
        return resp.getStatus();
    }

    private void floodThen429(String method, String path) throws Exception {
        String ip = "10.9.0." + (1 + Math.abs((method + path).hashCode()) % 200);
        for (int i = 0; i < 10; i++) {
            assertThat(doFilter(method, ip, path))
                .as("%s %s request %d should pass", method, path, i + 1)
                .isEqualTo(200);
        }
        assertThat(doFilter(method, ip, path))
            .as("%s %s 11th request must be 429", method, path)
            .isEqualTo(429);
    }

    @ParameterizedTest(name = "{0} {1} is gated by the data bucket")
    @CsvSource({
        "POST,   /deployments",
        "GET,    /users",
        "POST,   /users",
        "GET,    /dmn",
        "POST,   /dmn/x/evaluate",
        "GET,    /forms",
        "POST,   /forms",
        "GET,    /me/api-key",
        "POST,   /variable-schemas/generate",
        "GET,    /admin/audit-log",
        "POST,   /admin/outbox/123/redrive",
        "GET,    /admin/registrations",
        "PUT,    /admin/mail/settings",
        "POST,   /admin/mail/test-self",
        "GET,    /admin/users/123/memberships",
        "GET,    /processes/abc/members",
        "POST,   /processes/abc/members",
        "PATCH,  /processes/abc/members/u1",
        "DELETE, /processes/abc/members/u1",
        "GET,    /me/memberships",
        "PUT,    /users/123",
        "DELETE, /process-definitions/123/element-bindings/x",
    })
    void auditPath_gated(String method, String path) throws Exception {
        floodThen429(method.strip(), path.strip());
    }

    @Test
    void nonAdminUnrelatedPaths_stillPassThrough() throws Exception {
        // Auth-adjacent and infra paths must NOT be swallowed into the data bucket:
        // /auth/me and /auth/verify go through the chain untouched by any bucket.
        assertThat(doFilter("GET", "10.9.1.1", "/auth/me")).isEqualTo(200);
        assertThat(doFilter("GET", "10.9.1.1", "/auth/verify")).isEqualTo(200);
    }
}
