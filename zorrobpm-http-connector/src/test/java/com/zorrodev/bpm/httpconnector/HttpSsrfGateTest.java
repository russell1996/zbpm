package com.zorrodev.bpm.httpconnector;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-31 Фаза 2: SSRF-гейт — fail-closed на каждом шаге.
 * Без сети: литеральные IP не требуют DNS; {@code localhost} резолвится локально.
 */
class HttpSsrfGateTest {

    private static HttpSsrfGate gate(String allowedHosts, boolean allowPrivate) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setAllowedHosts(allowedHosts);
        props.setAllowPrivateNetworks(allowPrivate);
        return new HttpSsrfGate(props);
    }

    @Test
    void scheme_file_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("file:///etc/passwd")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("http/https");
    }

    @Test
    void scheme_gopher_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("gopher://example.com/1")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
    }

    @Test
    void userinfo_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://user:pass@example.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("userinfo");
    }

    @Test
    void emptyAllowlist_denyAll_evenForPublicLiteral() {
        // Решение CTO п.2: пустой allowed-hosts = deny-all (fail-closed), не fail-open по публичным.
        HttpSsrfGate gate = gate("", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://8.8.8.8/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("deny-all");
    }

    @Test
    void hostNotInAllowlist_rejected() {
        HttpSsrfGate gate = gate("api.example.com", true);
        assertThatThrownBy(() -> gate.validate(URI.create("http://evil.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
    }

    @Test
    void loopbackLiteral_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("127.0.0.1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://127.0.0.1:8080/actuator")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void privateRanges_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("10.0.0.1,172.20.5.5,192.168.1.1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://10.0.0.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
        assertThatThrownBy(() -> gate.validate(URI.create("http://172.20.5.5/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
        assertThatThrownBy(() -> gate.validate(URI.create("http://192.168.1.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
    }

    @Test
    void cloudMetadata_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("169.254.169.254", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://169.254.169.254/latest/meta-data/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void localhost_resolvesToLoopback_rejected_whenPrivateNotAllowed() {
        // Без внешней сети: localhost резолвится локально в 127.0.0.1 — гейт ловит ПОСЛЕ резолва.
        HttpSsrfGate gate = gate("localhost", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://localhost:15672/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void ipv6Loopback_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("::1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://[::1]:8080/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void publicLiteral_passes_whenAllowlisted() throws Exception {
        gate("8.8.8.8", false).validate(URI.create("http://8.8.8.8/"));
    }

    @Test
    void privateLiteral_passes_onlyWithExplicitOptIn() throws Exception {
        gate("127.0.0.1", true).validate(URI.create("http://127.0.0.1:8080/"));
    }

    @Test
    void suffixMatch_doesNotApplyToLiteralIps() {
        // "10.0.0.1" НЕ должен матчить запись "0.0.1" суффиксом — только точное совпадение для IP.
        HttpSsrfGate gate = gate("0.0.1", true);
        assertThatThrownBy(() -> gate.validate(URI.create("http://10.0.0.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
    }

    // --- Побайтовые проверки диапазонов (без DNS) ---

    @Test
    void privateOrReserved_ipv4_matrix() {
        // private + loopback + link-local + special-use
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(10, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 16, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 31, 255, 255))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(192, 168, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(127, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(169, 254, 169, 254))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(0, 0, 0, 0))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(100, 64, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(192, 0, 2, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(203, 0, 113, 5))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(198, 18, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(224, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(240, 0, 0, 1))).isTrue();
        // границы: 172.15.x и 172.32.x — уже публичные
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 15, 0, 1))).isFalse();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 32, 0, 1))).isFalse();
        // публичные
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(8, 8, 8, 8))).isFalse();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(1, 1, 1, 1))).isFalse();
    }

    @Test
    void privateOrReserved_ipv6_matrix() throws Exception {
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fe80::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("ff02::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("2001:db8::1"))).isTrue();
        // v4-mapped приватный — ловится через встроенный v4
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::ffff:10.0.0.1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::ffff:8.8.8.8"))).isFalse();
        // глобальный unicast — проходит
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void parseAllowedHosts_trimsAndLowercases() {
        assertThat(HttpSsrfGate.parseAllowedHosts(" API.Example.COM , ,internal.local "))
            .containsExactlyInAnyOrder("api.example.com", "internal.local");
        assertThat(HttpSsrfGate.parseAllowedHosts("")).isEmpty();
        assertThat(HttpSsrfGate.parseAllowedHosts(null)).isEmpty();
    }
}
