package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
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
 * WO-OBS-4: POF + contract tests for {@code GET /auth/verify} (nginx auth_request).
 *
 * <p>V11: full Spring context + real filter chain (mirrors CookieAuthIntegrationTest),
 * no fixed principals. POF core is {@link #validCookie_returns200WithIdentityHeaders}:
 * RED = 404 (no mapping) on the tree without the endpoint, GREEN = 200 +
 * X-Auth-User/X-Auth-Role after. Removing the endpoint reverts GREEN to RED.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthVerifyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String loginCookie() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        return setCookie.split("zbpm_token=")[1].split(";")[0];
    }

    private String bearerToken() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(loginResult.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/verify"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validCookie_returns200WithIdentityHeaders() throws Exception {
        String token = loginCookie();
        // Ground truth for expected identity: the same session via /auth/me.
        MvcResult meResult = mockMvc.perform(get("/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode me = mapper.readTree(meResult.getResponse().getContentAsString());

        mockMvc.perform(get("/auth/verify")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", token)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Auth-User", me.get("username").asText()))
                .andExpect(header().string("X-Auth-Role", me.get("role").asText()));
    }

    @Test
    void garbageToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/verify")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", "garbage-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requireRole_matchingRole_returns200() throws Exception {
        // Test-profile bootstrap admin is SUPER_ADMIN — the positive role-gate path
        // (nginx /prometheus/ for SUPER_ADMIN sessions).
        String token = loginCookie();
        mockMvc.perform(get("/auth/verify")
                        .queryParam("requireRole", "SUPER_ADMIN")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", token)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Auth-User"))
                .andExpect(header().string("X-Auth-Role", "SUPER_ADMIN"));
    }

    @Test
    void requireRole_mismatchedRole_returns403() throws Exception {
        // Same valid session, wrong role — hard gate lives in the endpoint because
        // nginx cannot evaluate auth_request_set variables in a location-level `if`
        // (it fires before the subrequest and would 403 everyone).
        String token = loginCookie();
        mockMvc.perform(get("/auth/verify")
                        .queryParam("requireRole", "NONEXISTENT_ROLE")
                        .cookie(new jakarta.servlet.http.Cookie("zbpm_token", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validBearer_returns200WithIdentityHeaders() throws Exception {
        String token = bearerToken();
        mockMvc.perform(get("/auth/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Auth-User"))
                .andExpect(header().exists("X-Auth-Role"));
    }
}
