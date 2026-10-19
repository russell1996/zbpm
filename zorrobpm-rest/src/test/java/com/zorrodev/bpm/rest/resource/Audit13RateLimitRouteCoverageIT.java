package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * WO-AUDIT-13 / F-06: the rate-limit covered only part of the mutating surface.
 *
 * <p>Audit evidence (docs/audit/ZorroBPM-full-audit-2026-10-19.md, F-06):
 * {@code RateLimitFilterConfig.rateLimitFilterRegistration} and
 * {@code RateLimitFilter.isDataEndpoint} did not mention
 * {@code /process-submissions*}, {@code /presets*}, {@code /messages*},
 * {@code /timer-jobs*}, {@code /message-subscriptions*},
 * {@code /auth/reset-password}, {@code /auth/accept-invitation},
 * {@code /auth/verify-email} — those routes were reachable WITHOUT any bucket.
 * Two more gaps of the same class were found while building the route matrix:
 * {@code POST /processes/{key}/archive|unarchive} (the {@code /processes} subtree
 * was registered but the guard only matched {@code contains("/members")}) and
 * {@code POST /auth/logout}.
 *
 * <p>V11: full Spring context + the REAL filter chain (MockMvc with the
 * {@code FilterRegistrationBean}-registered {@code RateLimitFilter}) — the proof
 * is a real HTTP 429 with the filter's own body, not a unit-level stub.
 *
 * <p>The requests are sent as an AUTHENTICATED admin (the threat model of
 * F-06 is "an authenticated user or a script with a token"), with bodies that
 * are rejected by bean validation, so no test case mutates state.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    // login bucket: high enough for the per-case logins (one per case)
    "zorrobpm.security.rate-limit.capacity=100",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=100",
    // data bucket: low, so the 6th request of every case is rate-limited
    "zorrobpm.security.rate-limit.data-capacity=5",
    "zorrobpm.security.rate-limit.data-window-seconds=3600",
    // auth-link bucket (public password-link endpoints): low, same reason
    "zorrobpm.security.rate-limit.auth-link-capacity=5",
    "zorrobpm.security.rate-limit.auth-link-window-seconds=3600",
    "zorrobpm.security.rate-limit.enabled=true"
})
class Audit13RateLimitRouteCoverageIT {

    /** A body that Jackson cannot bind (List expected, String given) → 400, nothing persisted. */
    private static final String MALFORMED_BODY = "{\"variables\":\"not-a-list\"}";
    /** A body that fails bean validation ({@code @NotBlank}/{@code @NotNull}) → 400. */
    private static final String EMPTY_BODY = "{}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /**
     * One route of the rate-limit matrix.
     *
     * @param bucket        the bucket the route must be limited by (documentation + failure message)
     * @param method        HTTP method
     * @param path          the request path
     * @param body          request body, or {@code null} for GET/DELETE
     * @param authenticated whether the request carries an admin token
     */
    private record RouteCase(String bucket, String method, String path, String body, boolean authenticated) {
        @Override
        public String toString() {
            return bucket + " " + method + " " + path;
        }
    }

    static Stream<RouteCase> routesNeedingCoverage() {
        return Stream.of(
            // ---- F-06: routes that had NO bucket at all ----
            new RouteCase("DATA", "POST", "/presets", MALFORMED_BODY, true),
            new RouteCase("DATA", "POST", "/presets/import", MALFORMED_BODY, true),
            new RouteCase("DATA", "PUT", "/presets/00000000-0000-0000-0000-000000000001", MALFORMED_BODY, true),
            new RouteCase("DATA", "DELETE", "/presets/00000000-0000-0000-0000-000000000001", null, true),
            new RouteCase("DATA", "POST", "/process-submissions", EMPTY_BODY, true),
            new RouteCase("DATA", "POST", "/process-submissions/00000000-0000-0000-0000-000000000001/approve", null, true),
            new RouteCase("DATA", "POST", "/messages/publish", EMPTY_BODY, true),
            new RouteCase("DATA", "GET", "/timer-jobs", null, true),
            new RouteCase("DATA", "GET", "/message-subscriptions", null, true),
            // ---- same class, found while building the matrix (not in the audit list) ----
            new RouteCase("DATA", "POST", "/processes/no-such-key/archive", null, true),
            new RouteCase("DATA", "POST", "/auth/logout", null, true),
            // ---- public password-link endpoints (unauthenticated, token in body) ----
            new RouteCase("AUTH_LINK", "POST", "/auth/reset-password", EMPTY_BODY, false),
            new RouteCase("AUTH_LINK", "POST", "/auth/accept-invitation", EMPTY_BODY, false),
            new RouteCase("AUTH_LINK", "POST", "/auth/verify-email", EMPTY_BODY, false),
            new RouteCase("AUTH_LINK", "POST", "/auth/register", EMPTY_BODY, false),
            new RouteCase("AUTH_LINK", "POST", "/auth/forgot-password", EMPTY_BODY, false),
            // ---- control: routes that were already limited (regression guard) ----
            new RouteCase("DATA", "GET", "/process-instances", null, true),
            new RouteCase("DATA", "GET", "/users", null, true)
        );
    }

    @ParameterizedTest(name = "{0} is rate-limited")
    @MethodSource("routesNeedingCoverage")
    void route_isRateLimitedAfterCapacity(RouteCase route) throws Exception {
        // Fresh buckets per case: the data bucket is SHARED by all data routes,
        // so without a reset the second case would start already exhausted.
        rateLimitFilter.reset();
        String token = route.authenticated() ? loginAsAdmin() : null;

        for (int i = 0; i < 5; i++) {
            int status = perform(route, token).getResponse().getStatus();
            assertThat(status)
                .as("%s request #%d must not be rate-limited yet", route, i + 1)
                .isNotEqualTo(429);
        }

        MvcResult limited = perform(route, token);
        assertThat(limited.getResponse().getStatus())
            .as("%s request #6 must be rate-limited", route)
            .isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString())
            .as("%s 429 body must come from the rate-limit filter", route)
            .contains("RATE_LIMITED");
        assertThat(limited.getResponse().getHeader("Retry-After"))
            .as("%s 429 must carry Retry-After", route)
            .isNotNull();
    }

    private MvcResult perform(RouteCase route, String token) throws Exception {
        MockHttpServletRequestBuilder request;
        switch (route.method()) {
            case "GET" -> request = get(route.path());
            case "POST" -> request = post(route.path());
            case "PUT" -> request = put(route.path());
            case "DELETE" -> request = delete(route.path());
            default -> throw new IllegalArgumentException("unsupported method " + route.method());
        }
        if (route.body() != null) {
            request = request.content(route.body()).contentType(MediaType.APPLICATION_JSON);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String loginAsAdmin() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(result.getResponse().getStatus())
            .as("admin login must succeed")
            .isEqualTo(200);
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
