package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String validToken;

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
        validToken = authResponse.getToken();
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
}
