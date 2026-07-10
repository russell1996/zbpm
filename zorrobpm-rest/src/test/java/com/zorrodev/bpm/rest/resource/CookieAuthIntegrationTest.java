package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-6: Cookie-based auth tests.
 *  #1: login sets httpOnly cookie
 *  #2: request with cookie (no Authorization header) → authorized
 *  #3: Bearer header still works (backward compat)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CookieAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    // --- Criterion #1: login sets httpOnly cookie ---

    @Test
    void criterion1_login_setsHttpOnlyCookie() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie",
                    org.hamcrest.Matchers.containsString("zbpm_token=")))
                .andExpect(header().string("Set-Cookie",
                    org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    // --- Criterion #2: request with cookie → authorized ---

    @Test
    void criterion2_requestWithCookie_returns200() throws Exception {
        // Login to get the cookie value
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        String token = setCookie.split("zbpm_token=")[1].split(";")[0];

        // Use cookie (no Authorization header)
        mockMvc.perform(get("/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", token)))
                .andExpect(status().isOk());
    }

    // --- Criterion #3: Bearer header still works ---

    @Test
    void criterion3_bearerHeader_stillWorks() throws Exception {
        // Login to get the token
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        String token = setCookie.split("zbpm_token=")[1].split(";")[0];

        // Use Bearer header
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
