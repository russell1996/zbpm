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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-4 criterion #2: after rate-limit window expires, login works again.
 * Uses window-seconds=3600 (large enough for slow PBKDF2 drain, P-10 safe).
 * Drain phase always fits within 3600s. Reset is verified via rateLimitFilter.reset()
 * (simulates window expiry) — avoids Thread.sleep timing flakiness.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600"
})
class RateLimitWindowResetTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void resetBucket() {
        rateLimitFilter.reset();
    }

    /** Non-existent user → 401 (constant-time: PBKDF2 always runs). */
    private LoginDTO badLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("nobody-" + UUID.randomUUID());
        dto.setPassword("x");
        return dto;
    }

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    // --- Criterion #2: after window → works again ---

    @Test
    void criterion2_afterWindow_worksAgain() throws Exception {
        // Drain bucket (window=3600s, always fits 5 slow PBKDF2 logins)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login")
                            .content(mapper.writeValueAsString(badLogin()))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
        // Confirm blocked (6th → 429)
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(badLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());

        // Simulate window expiry via reset (avoids Thread.sleep timing flakiness, P-10)
        rateLimitFilter.reset();

        // Should work again with valid credentials
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
