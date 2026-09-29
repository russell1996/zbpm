package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-79 (NEW-10) criterion 1: full-context proof through the real filter
 * chain (V11) — 100 refresh requests with random unverified cookies from one
 * loopback address must 429 after the IP capacity is exhausted. On the old
 * code (per-"user" bucket only, fresh key per random cookie) nothing ever
 * fired — this test documents that RED (see the report) and pins the fix.
 *
 * <p>Criterion 2 (legitimate user untouched) is covered by
 * {@code RefreshTokenIntegrationTest} (valid refresh → 200 through the same
 * chain) plus the unit-level {@code sameCookieFromDifferentIps} case; the
 * full-context half of criterion 2 is the absence of false 429s in those
 * suites under the same capacity profile.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.refresh-capacity=100",
    "zorrobpm.security.rate-limit.refresh-window-seconds=3600",
    // WO-QW-5 (NEW2-11): IP-бакет refresh — свой ключ; удерживаем 5 для
    // этого flood-сценария (доказательство, что IP-лимит жив), разделение
    // от capacity логина доказывает Sec79RefreshIpBucketTest новым тестом.
    "zorrobpm.security.rate-limit.refresh-ip-capacity=5",
    "server.forward-headers-strategy=framework"
})
class Sec79RefreshFloodFullContextIT {

    @LocalServerPort
    private int port;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
    }

    @Test
    void randomCookieFloodFromOneAddress_hits429() throws Exception {
        int limited = 0;
        for (int i = 0; i < 100; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/refresh"))
                .header("Content-Type", "application/json")
                .header("Cookie", "refresh_token=random-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) limited++;
        }
        assertThat(limited)
            .as("100 random-cookie refreshes from one address must mostly 429 after IP capacity 5")
            .isGreaterThanOrEqualTo(90);
    }
}
