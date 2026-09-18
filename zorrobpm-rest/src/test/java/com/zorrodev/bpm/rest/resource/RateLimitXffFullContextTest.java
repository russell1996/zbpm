package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-13: Full-context XFF spoofing resistance test.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=10",
    "server.forward-headers-strategy=framework"
})
class RateLimitXffFullContextTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private ApplicationContext ctx;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
    }

    @Test
    void rateLimitFilter_registeredOnAuthAndDataEndpoints_highestPrecedence() {
        // WO-TEST-3 T-03: the old debug_allBeans only PRINTED beans — no assertions, so it
        // looked like coverage while asserting nothing (even a fully unregistered filter chain
        // was "green"). Assert the registration contract from RateLimitFilterConfig instead:
        // exactly one registration, HIGHEST_PRECEDENCE, covering every auth + data endpoint.
        List<FilterRegistrationBean> registrations = ctx.getBeansOfType(FilterRegistrationBean.class)
            .values().stream()
            .filter(b -> b.getUrlPatterns().contains("/auth/login"))
            .toList();

        assertThat(registrations).hasSize(1);
        FilterRegistrationBean registration = registrations.get(0);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
            "/auth/login", "/auth/refresh", "/me/password",
            "/events/*", "/variables/*",
            "/process-instances/*", "/user-tasks/*",
            "/service-tasks/*", "/incidents/*",
            "/process-definitions/*");
    }

    private HttpRequest buildLoginRequest(String xffValue) throws Exception {
        LoginDTO loginDto = new LoginDTO();
        loginDto.setUsername("admin");
        loginDto.setPassword("admin");
        String body = mapper.writeValueAsString(loginDto);
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/auth/login"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (xffValue != null) {
            builder.header("X-Forwarded-For", xffValue);
        }
        return builder.build();
    }

    @Test
    void criterion1_xffSpoof_sameRemoteAddr_hits429() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> resp = httpClient.send(buildLoginRequest("10.0.0." + (i + 1)),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode())
                    .as("Request %d with XFF=10.0.0.%d should pass", i, i + 1)
                    .isEqualTo(200);
        }
        HttpResponse<String> resp = httpClient.send(buildLoginRequest("10.0.0.99"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode())
                .as("6th request should be rate-limited despite different XFF")
                .isEqualTo(429);
    }
}
