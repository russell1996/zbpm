package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-SEC-79 (NEW-10): the refresh path throttled ONLY a per-"user" bucket
 * keyed on the first 16 chars of the UNVERIFIED cookie — a random cookie per
 * request meant a fresh bucket every time, so the limit never fired (and each
 * request INSERTed a new bucket row). Like the login path, refresh must hit
 * an IP bucket FIRST, independent of whether the token exists.
 */
class Sec79RefreshIpBucketTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(5);
        filter.setWindowSeconds(3600);
        filter.setAccountCapacity(5);
        filter.setRefreshCapacity(100);
        filter.setRefreshWindowSeconds(3600);
        // WO-QW-5: IP-бакет refresh — свой (refreshIpCapacity), удерживаем 5
        // для старых сценариев класса (порог срабатывания IP-лимита), новый
        // тест ниже ставит 60 и доказывает разделение от capacity логина.
        filter.setRefreshIpCapacity(5);
        filter.setDataCapacity(120);
        filter.setDataWindowSeconds(60);
        filter.setRateLimitEnabled(true);
        filter.setTrustedProxies(Set.of());
        filter.reset();
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
    void randomCookiesFromOneIp_hitIpLimit() throws Exception {
        // 100 random unverified cookies from ONE ip: refreshCapacity=100 on the
        // per-user bucket can never fire (every key is fresh), so any 429 must
        // come from the new IP bucket (capacity=5 → 6th request 429s).
        int limited = 0;
        for (int i = 0; i < 100; i++) {
            int status = doFilter(refreshRequest("10.7.7.7", "random-" + UUID.randomUUID()));
            if (status == 429) limited++;
        }
        assertThat(limited)
            .as("100 random-cookie refreshes from one IP must hit the IP bucket (6th+ request 429)")
            .isGreaterThanOrEqualTo(90);
    }

    @Test
    void sameCookieFromDifferentIps_userBucketStillApplies() throws Exception {
        // Same cookie, fresh IP each time: the IP bucket never fills (capacity
        // 5 per IP, one request per IP), so the per-user bucket (capacity 100)
        // must NOT fire either — but different cookies on the SAME ip must.
        for (int i = 0; i < 50; i++) {
            int status = doFilter(refreshRequest("10.8.0." + (i + 1), "same-cookie-value-abcdef"));
            assertThat(status)
                .as("same cookie from fresh IP %d must pass (neither bucket full)", i)
                .isEqualTo(200);
        }
    }
    @Test
    void cookiePrefixCollision_sameFullCookie_differentPrefixTreatedEqual() throws Exception {
        // Two cookies sharing the first 16 chars but differing after must land
        // in DIFFERENT buckets after the fix (full-cookie hash key). On the old
        // prefix-16 key they shared one bucket — this test pins the new keying.
        filter.setRefreshCapacity(2);
        String base = "shared-prefix-16";
        assertThat(doFilter(refreshRequest("10.9.0.1", base + "-variant-AAA"))).isEqualTo(200);
        assertThat(doFilter(refreshRequest("10.9.0.2", base + "-variant-AAA"))).isEqualTo(200);
        // A different full cookie with the same 16-char prefix must NOT consume
        // variant-AAA's bucket: passes on its own budget.
        assertThat(doFilter(refreshRequest("10.9.0.3", base + "-variant-BBB")))
            .as("different full cookie, same 16-char prefix → separate bucket → 200")
            .isEqualTo(200);
        // While the first cookie's own budget exhausts on its 3rd use.
        assertThat(doFilter(refreshRequest("10.9.0.4", base + "-variant-AAA")))
            .as("3rd use of variant-AAA with capacity 2 → 429")
            .isEqualTo(429);
    }

    /**
     * WO-QW-5 (NEW2-11): N пользователей за одним IP не душат друг друга.
     * IP-бакет refresh — свой (`refreshIpCapacity`), не `capacity` логина:
     * 8 разных кук с одного IP при login-capacity=5 обязаны пройти все
     * (раньше 6-й получал 429). POF-мутация: `refreshIpCapacity` → `capacity`
     * в IP-ветке refresh — этот тест КРАСНЫЙ (6-й refresh 429).
     */
    @Test
    void sharedIp_manyUsersEachRefreshOnce_allPass() throws Exception {
        filter.setRefreshIpCapacity(60);
        for (int i = 0; i < 8; i++) {
            int status = doFilter(refreshRequest("10.99.0.1", "user-" + i + "-cookie-abcdef"));
            assertThat(status)
                .as("пользователь %d за общим IP: свой refresh в пределах лимита обязан пройти", i)
                .isEqualTo(200);
        }
    }
}
