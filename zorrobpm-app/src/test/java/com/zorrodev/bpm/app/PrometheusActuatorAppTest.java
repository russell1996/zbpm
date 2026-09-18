package com.zorrodev.bpm.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-1 item 1 (TD7): /actuator/prometheus must be exposed in the DEPLOYED
 * unit — zorrobpm-app — not just in zorrobpm-rest's properties (which lose to the
 * app's application.properties on the runtime classpath → 404 in prod).
 *
 * Same security posture as WO-OBS-1 on rest: health public, prometheus behind auth
 * (JwtAuthFilter deny-by-default, service API key scrape).
 *
 * Test-env note (found live): a src/test/resources/application.properties SHADOWS the main
 * one entirely in this setup (single config resource in the Environment, main-only keys
 * read as null) — so test plumbing here is inline @SpringBootTest properties (individual
 * keys override, files still merge) and NEVER a management.* line: the exposure MUST come
 * from the MAIN application.properties, otherwise the RED below would be fake.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = APP.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:test",
    "spring.rabbitmq.host=localhost",
    "spring.rabbitmq.port=11002",
    "spring.rabbitmq.username=zorrodev",
    "spring.rabbitmq.password=zorrodev",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "app.filesDir=target/files",
    "zorrobpm.security.rate-limit.enabled=false",
    "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusActuatorAppTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        adminToken = authResponse.getToken();
    }

    @Test
    void prometheus_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_withApiKey_returns200WithZbpmMetrics() throws Exception {
        UiUserEntity svcUser = new UiUserEntity();
        svcUser.setId(UUID.randomUUID());
        svcUser.setUsername("prom-scraper-app");
        svcUser.setPasswordHash(passwordHasher.hash("scrape"));
        svcUser.setFullName("Prometheus scraper");
        svcUser.setRole("USER");
        svcUser.setActive(true);
        svcUser.setCreatedAt(Instant.now());
        svcUser.setUpdatedAt(Instant.now());
        userRepository.save(svcUser);

        MvcResult keyResult = mockMvc.perform(post("/admin/users/" + svcUser.getId() + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        String apiKey = mapper.readTree(keyResult.getResponse().getContentAsString()).get("key").asText();

        MvcResult scrape = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andReturn();
        String body = scrape.getResponse().getContentAsString();
        // BpmMetrics meters are registered at context startup — visible even at zero.
        // (Micrometer renders zbpm.process.started as zbpm_process_started_total.)
        org.assertj.core.api.Assertions.assertThat(body).contains("zbpm_process_started_total");
    }
}
