package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-QW-9 (NEW4-08): throttled self-registration answers 429 + Retry-After,
 * not 422. Full MockMvc chain (real filter chain + real
 * {@code GlobalExceptionHandler}) — the status and the header are asserted,
 * not just the body code.
 *
 * <p>POF link: on the unfixed code the service throws plain
 * {@code EngineException} → {@code handleEngineError} → 422 with no
 * Retry-After; both assertions below go RED. Only the narrow exception +
 * dedicated handler removes that RED.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RegistrationRateLimitHttpIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired @Qualifier("registrationRateLimiter") private PasswordResetRateLimiter registerLimiter;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void restore() {
        registerLimiter.reset();
        registerLimiter.setEmailCapacity(5);
        registerLimiter.setEmailWindowSeconds(3600);
        registerLimiter.setIpCapacity(20);
        registerLimiter.setIpWindowSeconds(3600);
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupIds.clear();
    }

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String body(String username, String email) {
        return "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\","
            + "\"fullName\":\"Self\",\"email\":\"" + email + "\"}";
    }

    @Test
    void register_ipThrottled_answers429WithRetryAfter() throws Exception {
        // Test-narrowed window (same technique as the engine-level rate tests):
        // 1 per IP, 60s window — the second request from the same IP must 429.
        registerLimiter.reset();
        registerLimiter.setIpCapacity(1);
        registerLimiter.setIpWindowSeconds(60);
        String ip = "10.200." + tag().replace("-", "").substring(0, 4) + ".1";

        String email1 = "rlhttp1-" + tag() + "@x.com";
        MvcResult first = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rlhttp1-" + tag(), email1).getBytes(StandardCharsets.UTF_8))
                        .with(req -> { req.setRemoteAddr(ip); return req; }))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(first.getResponse().getHeader("Retry-After")).isNull();

        userRepository.findAll().stream()
            .filter(u -> email1.equals(u.getEmail()))
            .map(UiUserEntity::getId)
            .forEach(cleanupIds::add);

        // Fresh email + username, SAME IP: the per-IP bucket is exhausted.
        String email2 = "rlhttp2-" + tag() + "@x.com";
        MvcResult throttled = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rlhttp2-" + tag(), email2).getBytes(StandardCharsets.UTF_8))
                        .with(req -> { req.setRemoteAddr(ip); return req; }))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(throttled.getResponse().getHeader("Retry-After"))
            .as("Retry-After header carries the exhausted bucket window")
            .isEqualTo("60");
        assertThat(throttled.getResponse().getContentAsString())
            .as("body code is RATE_LIMITED, not ENGINE_ERROR")
            .contains("\"code\":\"RATE_LIMITED\"");
    }
}
