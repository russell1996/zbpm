package com.zorrodev.bpm.rest.resource;

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
 * WO-SEC-1 integration tests: JWT auth for all data API endpoints.
 *
 * Criteria verified:
 *  #1 GET /process-instances without token → 401
 *  #2 GET /user-tasks without token → 401
 *  #3 POST /process-instances without token → 401
 *  #4 POST /user-tasks/{id}/complete without token → 401
 *  #5 With valid Bearer → 200
 *  #6 /auth/login without token → 200
 *  #8 Proof-of-failure: tests #1-3 were RED on current code before fix
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtAuthFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String validToken;
    private String userToken;

    @BeforeAll
    void login() throws Exception {
        // Create a USER-role user for 403 test
        UiUserEntity userEntity = new UiUserEntity();
        userEntity.setId(UUID.randomUUID());
        userEntity.setUsername("regular-user");
        userEntity.setPasswordHash(passwordHasher.hash("user"));
        userEntity.setFullName("Regular User");
        userEntity.setRole("USER");
        userEntity.setActive(true);
        userEntity.setCreatedAt(Instant.now());
        userEntity.setUpdatedAt(Instant.now());
        userRepository.save(userEntity);

        // Login as admin
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        validToken = authResponse.getToken();

        // Login as USER-role user
        LoginDTO userLoginDTO = new LoginDTO();
        userLoginDTO.setUsername("regular-user");
        userLoginDTO.setPassword("user");

        MvcResult userResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(userLoginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse userAuthResponse = mapper.readValue(userResult.getResponse().getContentAsString(), AuthResponse.class);
        userToken = userAuthResponse.getToken();
    }

    // --- Criteria #1-4: without token → 401 ---

    @Test
    void criterion1_getProcessInstances_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/process-instances"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void criterion2_getUserTasks_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/user-tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void criterion3_postProcessInstances_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void criterion4_postUserTaskComplete_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/user-tasks/00000000-0000-0000-0000-000000000000/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Criterion #5: with valid token → 200 ---

    @Test
    void criterion5_getProcessInstances_withValidToken_returns200() throws Exception {
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());
    }

    // --- Criterion #6: /auth/login without token → 200 ---

    @Test
    void criterion6_authLogin_withoutToken_returns200() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Other data API endpoints ---

    @Test
    void users_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void users_nonAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getVariables_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/variables"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getIncidents_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimerJobs_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/timer-jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMessageSubscriptions_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/message-subscriptions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProcessDefinitions_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/process-definitions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDmn_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/dmn"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getServiceTasks_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/service-tasks"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== WO-AUD-4 F8: /forms/{key} must require auth ====================

    @Test
    void aud4_getFormByKey_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/forms/some-form-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aud4_getFormsList_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/forms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aud4_postForms_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"test\",\"schema\":\"{}\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== WO-SEC-26: deny-by-default ====================

    /** POF: /variable-schemas/generate was anonymous (400 from controller), now 401 */
    @Test
    void sec26_variableSchemasGenerate_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/variable-schemas/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[{\"name\":\"x\",\"type\":\"string\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    /** Public paths must STILL work without token */
    @Test
    void sec26_authLogin_withoutToken_stillWorks() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void sec26_authRefresh_withoutToken_stillWorks() throws Exception {
        // Refresh with empty body — controller returns its own error (not filter's "Unauthorized")
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // Filter returns "Unauthorized" as plain text; controller returns JSON error
                    // The key: the request REACHED the controller, it wasn't blocked by the filter
                    org.assertj.core.api.Assertions.assertThat(body)
                        .as("Refresh endpoint must pass through filter (controller processes it)")
                        .isNotEqualTo("Unauthorized");
                });
    }

    // ==================== WO-OPS-1: /actuator/health public ====================

    @Test
    void ops1_actuatorHealth_withoutToken_notBlockedByFilter() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Filter must NOT return 401. 200=UP, 503=DOWN (components unavailable in test)
                    // — both mean the request reached actuator, not blocked by filter.
                    org.assertj.core.api.Assertions.assertThat(status)
                        .as("actuator/health must pass through filter (not 401)")
                        .isNotEqualTo(401);
                });
    }

    @Test
    void ops1_actuatorEnv_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== WO-OBS-1: /actuator/prometheus authenticated scrape ====================

    @Test
    void obs1_prometheus_withoutToken_returns401() throws Exception {
        // Criterion 2: not open anonymously — deny-by-default, no JwtAuthFilter change.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void obs1_prometheus_withApiKey_returns200WithZbpmMetrics() throws Exception {
        // Internal scrape uses a service API key (zbpm_sk_ mechanism, no filter bypass).
        UiUserEntity svcUser = new UiUserEntity();
        svcUser.setId(UUID.randomUUID());
        svcUser.setUsername("prom-scraper");
        svcUser.setPasswordHash(passwordHasher.hash("scrape"));
        svcUser.setFullName("Prometheus scraper");
        svcUser.setRole("USER");
        svcUser.setActive(true);
        svcUser.setCreatedAt(Instant.now());
        svcUser.setUpdatedAt(Instant.now());
        userRepository.save(svcUser);

        MvcResult keyResult = mockMvc.perform(post("/admin/users/" + svcUser.getId() + "/api-key")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isCreated())
                .andReturn();
        String apiKey = mapper.readTree(keyResult.getResponse().getContentAsString()).get("key").asText();

        MvcResult scrape = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andReturn();
        String body = scrape.getResponse().getContentAsString();
        // BpmMetrics meters are registered at context startup — visible even at zero.
        org.assertj.core.api.Assertions.assertThat(body).contains("zbpm_process_started_total");
    }
}
