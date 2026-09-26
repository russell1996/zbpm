package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-4: Rate-limit on /auth/login — deterministic tests.
 *  #1: 6th attempt → 429 RATE_LIMITED (window=3600, all 6 in one window)
 *  #3: first successful login not blocked
 *
 * window-seconds=3600 ensures all 6 logins are guaranteed within one window
 * regardless of PBKDF2 speed (P-10 fix).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=10"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void resetBucket() {
        rateLimitFilter.reset();
    }

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    // --- Criterion #3: first successful login not blocked ---

    @Test
    void criterion3_firstLogin_works() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #1: 6th attempt → 429 ---

    @Test
    void criterion1_sixthAttempt_returns429() throws Exception {
        // Consume 5 tokens (capacity=5)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login")
                            .content(mapper.writeValueAsString(validLogin()))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
        // 6th attempt → 429
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }
}
