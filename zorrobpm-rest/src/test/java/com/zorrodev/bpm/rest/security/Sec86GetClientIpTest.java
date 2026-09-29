package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-86: {@code RateLimitFilter.getClientIp} различает клиентов за доверенным
 * прокси по {@code X-Real-IP} и игнорирует прокси-заголовки от недоверенного пира.
 *
 * <p>POF-мутация: вернуть старое тело ({@code return remoteAddr} в обеих ветках) —
 * {@code trustedPeer_usesXRealIp} и {@code trustedCidr_usesXRealIp} КРАСНЫЕ
 * (возвращают адрес прокси вместо адреса клиента).
 */
class Sec86GetClientIpTest {

    private static final String PROXY = "172.18.0.3";

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setRateLimitEnabled(false);
    }

    private MockHttpServletRequest request(String remoteAddr, String realIp, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
        req.setRemoteAddr(remoteAddr);
        if (realIp != null) {
            req.addHeader("X-Real-IP", realIp);
        }
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    @Test
    void trustedPeer_usesXRealIp() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request(PROXY, "203.0.113.66", null)))
            .as("peer is trusted, X-Real-IP must win")
            .isEqualTo("203.0.113.66");
    }

    @Test
    void trustedCidr_usesXRealIp() {
        filter.setTrustedProxies(Set.of("172.18.0.0/16"));
        assertThat(filter.getClientIp(request(PROXY, "198.51.100.7", null)))
            .as("peer inside trusted CIDR, X-Real-IP must win")
            .isEqualTo("198.51.100.7");
    }

    @Test
    void trustedPeer_xRealIpTrimmed() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request(PROXY, "  203.0.113.66  ", null)))
            .isEqualTo("203.0.113.66");
    }

    @Test
    void untrustedPeer_spoofedXRealIpIgnored() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request("10.9.9.9", "203.0.113.66", null)))
            .as("peer NOT in trusted set — forged X-Real-IP must be ignored (SEC-4)")
            .isEqualTo("10.9.9.9");
    }

    @Test
    void emptyTrustedSet_xRealIpIgnored() {
        filter.setTrustedProxies(Set.of());
        assertThat(filter.getClientIp(request(PROXY, "203.0.113.66", null)))
            .as("no proxies configured — header must be ignored, secure default")
            .isEqualTo(PROXY);
    }

    @Test
    void trustedPeer_missingHeader_fallsBackToRemoteAddr() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request(PROXY, null, null)))
            .isEqualTo(PROXY);
    }

    @Test
    void trustedPeer_blankHeader_fallsBackToRemoteAddr() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request(PROXY, "   ", null)))
            .isEqualTo(PROXY);
    }

    @Test
    void trustedPeer_xffNeverRead() {
        filter.setTrustedProxies(Set.of(PROXY));
        assertThat(filter.getClientIp(request(PROXY, null, "203.0.113.66, 10.0.0.1")))
            .as("X-Forwarded-For is never a source, even from a trusted peer (SEC-4)")
            .isEqualTo(PROXY);
    }
}
