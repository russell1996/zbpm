package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-API-4 (Finding #2, High), criterion #3: the audit found these admin/aux
 * paths outside ANY rate-limit bucket. They now share the data bucket.
 *
 * <p>V11: full Spring context + the real filter chain (MockMvc, same mechanism
 * as {@code Sec64CsrfHeadersRatelimitIT}/{@code Diff6ServiceTaskPollingRateLimitIT}):
 * exceeding the small test data-capacity on admin paths returns a real 429.
 * A dedicated registration test pins the second half of the wiring (a guard
 * without registration is dead code — the filter never executes).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.data-capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.data-window-seconds=3600"
})
class Api4AdminRateLimitIT {

    @Autowired MockMvc mockMvc;
    @Autowired RateLimitFilter rateLimitFilter;
    @Autowired ApplicationContext ctx;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @BeforeEach
    void resetBuckets() {
        rateLimitFilter.reset();
    }

    // ==================== Criterion #3: admin paths gated, one shared bucket ====================

    @Test
    void criterion3_mixedAdminPaths_shareOneDataBucket_floodReturns429() throws Exception {
        // 3 × /dmn + 2 × /users all pass (5 tokens of the SAME data bucket) ...
        for (int i = 0; i < 3; i++) {
            assertThat(getAsAdmin("/dmn")).as("GET /dmn #%d must pass", i + 1).isEqualTo(200);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(getAsAdmin("/users")).as("GET /users #%d must pass", i + 1).isEqualTo(200);
        }
        // ... so a 6th admin read on a THIRD prefix trips the shared bucket.
        MvcResult limited = mockMvc.perform(get("/forms")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        assertThat(limited.getResponse().getStatus())
            .as("6th admin read across prefixes must hit the shared data bucket")
            .isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void criterion3_postDeployments_floodReturns429() throws Exception {
        // POST /deployments (the batch path itself) is gated too: 5 × 400
        // (empty batch — service rejects, but the filter already consumed a token)
        // then the 6th is stopped by the filter with 429 before reaching MVC.
        for (int i = 0; i < 5; i++) {
            int s = mockMvc.perform(post("/deployments")
                    .header("Authorization", "Bearer " + adminToken)
                    .content("{\"resources\":[]}")
                    .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
            assertThat(s).as("POST /deployments #%d must reach the service (400 empty batch)", i + 1)
                .isEqualTo(400);
        }
        int s = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn().getResponse().getStatus();
        assertThat(s).as("6th POST /deployments must be rate-limited").isEqualTo(429);
    }

    @Test
    void criterion3_adminAuditLog_gated() throws Exception {
        // /admin/* prefix through the same bucket: 5 pass, 6th is 429.
        for (int i = 0; i < 5; i++) {
            assertThat(getAsAdmin("/admin/audit-log?limit=1"))
                .as("GET /admin/audit-log #%d must pass", i + 1).isEqualTo(200);
        }
        assertThat(getAsAdmin("/admin/audit-log?limit=1"))
            .as("6th GET /admin/audit-log must be rate-limited").isEqualTo(429);
    }

    // ==================== Registration: guard without registration is dead code ====================

    @Test
    void registration_namesEveryAdminPrefix() {
        List<FilterRegistrationBean> registrations = ctx.getBeansOfType(FilterRegistrationBean.class)
            .values().stream()
            .filter(b -> b.getUrlPatterns().contains("/auth/login"))
            .toList();

        assertThat(registrations).hasSize(1);
        assertThat(registrations.get(0).getUrlPatterns()).contains(
            "/deployments", "/deployments/*",
            "/users", "/users/*",
            "/dmn", "/dmn/*",
            "/forms", "/forms/*",
            "/me/api-key", "/me/api-key/*",
            "/me/memberships", "/me/memberships/*", "/me/*",
            "/variable-schemas/*",
            "/processes/*",
            "/admin/*");
    }

    private int getAsAdmin(String path) throws Exception {
        return mockMvc.perform(get(path)
                .header("Authorization", "Bearer " + adminToken))
            .andReturn().getResponse().getStatus();
    }

    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
