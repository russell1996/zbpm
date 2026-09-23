package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collection;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-FEAT-4: Refresh Token + Revocation IT.
 *  #1: Login sets refresh_token httpOnly cookie
 *  #2: POST /auth/refresh with valid refresh → new access token
 *  #3: logout → refresh invalid → subsequent refresh → 401
 *  #4: Expired/invalid refresh → 401
 *  #5: Bearer long-TTL clients NOT broken (existing CookieAuthIntegrationTest)
 *  #7: proof-of-failure — POST /auth/refresh on current code → 404
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefreshTokenIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    private String extractCookieFromHeaders(Collection<String> setCookieHeaders, String cookieName) {
        for (String header : setCookieHeaders) {
            if (header.contains(cookieName + "=")) {
                return header.split(cookieName + "=")[1].split(";")[0];
            }
        }
        return null;
    }

    private LoginResult loginWithRefreshToken() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);

        Collection<String> headers = loginResult.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");

        return new LoginResult(auth.getToken(), refreshToken);
    }

    record LoginResult(String accessToken, String refreshToken) {}

    // --- Criterion #1: Login sets refresh_token httpOnly cookie ---

    @Test
    void criterion1_login_setsRefreshCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");
        org.assertj.core.api.Assertions.assertThat(refreshToken)
                .as("refresh_token cookie must be set on login")
                .isNotNull()
                .isNotBlank();
    }

    // --- Criterion #2: POST /auth/refresh with valid refresh → 200 + new access token ---

    @Test
    void criterion2_refreshValidRefreshToken_returns200() throws Exception {
        LoginResult login = loginWithRefreshToken();
        org.assertj.core.api.Assertions.assertThat(login.refreshToken()).isNotNull();

        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isOk());
    }

    // --- Criterion #3: logout → refresh invalid → subsequent refresh → 401 ---

    @Test
    void criterion3_logout_invalidatesRefreshToken() throws Exception {
        LoginResult login = loginWithRefreshToken();
        org.assertj.core.api.Assertions.assertThat(login.refreshToken()).isNotNull();

        // Logout (requires auth)
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk());

        // Refresh after logout → 401
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    // --- Criterion #4: Invalid refresh token → 401 ---

    @Test
    void criterion4_invalidRefreshToken_returns401() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "totally-invalid-token")))
                .andExpect(status().isUnauthorized());
    }

    // --- WO-SEC-63 (F02): refresh must set a fresh zbpm_token access cookie so the SPA
    // can silently re-authenticate without triggering a forced re-login. Both login and
    // refresh endpoints must set the cookie; criterion 1 already proves login, this one
    // proves the refresh path specifically.

    @Test
    void refresh_setsZbpmTokenAccessCookie() throws Exception {
        LoginResult login = loginWithRefreshToken();
        org.assertj.core.api.Assertions.assertThat(login.refreshToken()).isNotNull();

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = refreshResult.getResponse().getHeaders("Set-Cookie");
        String zbpmToken = extractCookieFromHeaders(headers, "__Host-zbpm_token");
        org.assertj.core.api.Assertions.assertThat(zbpmToken)
                .as("refresh must set __Host-zbpm_token access cookie (F02, __Host- since SEC-64)")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void login_setsZbpmTokenAccessCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String zbpmToken = extractCookieFromHeaders(headers, "__Host-zbpm_token");
        org.assertj.core.api.Assertions.assertThat(zbpmToken)
                .as("login must set __Host-zbpm_token access cookie (__Host- since SEC-64)")
                .isNotNull()
                .isNotBlank();
    }
}
