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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-8 proof-of-failure #4:
 * POST with malformed JSON (variables:{} instead of []) → currently 500 INTERNAL_ERROR.
 * After fix → 400 MALFORMED_REQUEST.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobalExceptionHandlerParseErrorTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String token;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        token = authResponse.getToken();
    }

    @Test
    void malformedJson_returns400_not500() throws Exception {
        // POST with variables:{} (object instead of array) → should be 400, not 500
        String malformedBody = """
            {"processDefinitionKey":"process1","variables":{}}
            """;

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(malformedBody)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
