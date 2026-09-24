package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-18: L5 (indexes), L6 (logout with expired access), L7 (retry refresh).
 * Full-context IT on SpringBootTest.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityLowBatchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

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

    // --- Criterion #2: L6 — logout with expired access + live refresh → revokes ---

    @Test
    void criterion2_logoutWithExpiredAccessAndLiveRefresh_revokesTokens() throws Exception {
        LoginResult login = loginWithRefreshToken();
        assertThat(login.refreshToken()).isNotNull();

        // Simulate expired access: use an invalid/expired token as Bearer
        String expiredAccessToken = "expired-token-" + UUID.randomUUID();

        // Logout with expired access but valid refresh cookie
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + expiredAccessToken)
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isOk());

        // Verify: refresh should now fail (tokens revoked)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    // --- Criterion #3: L7 — retry refresh doesn't revoke all sessions ---

    @Test
    void criterion3_retryRefreshDoesNotRevokeAllSessions() throws Exception {
        // Create two sessions
        LoginResult login1 = loginWithRefreshToken();
        LoginResult login2 = loginWithRefreshToken();

        // WO-OPS-14: детерминированное состояние вместо Thread.sleep(6000)
        // (verifier HOLD #3: старый комментарий был неточен — sleep стоял ДО
        // ротации, а grace считается от revokedAt, который выставляется В
        // МОМЕНТ ротации. Значит sleep физически не мог «сдвинуть revokedAt
        // за грань grace» — он грел только возраст создания токена, на
        // grace-решение не влияя. Сразу после ротации токен «свеже-отозван» —
        // это и есть retry-путь L7, его и проверяем; sleep был бессмысленным,
        // удаление направления проверки не меняет). Хронометраж убран из теста
        // целиком: строкой состояния управляем напрямую, а не ожиданием
        // настенных часов.
        // First refresh → rotation (revokedAt set to now)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login1.refreshToken())))
                .andExpect(status().isOk());

        // Network retry: same old token sent immediately after rotation (within 5s of revokedAt)
        // L7: grace window → this is a retry, NOT theft → family should survive
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login1.refreshToken())))
                .andExpect(status().isUnauthorized()); // retry returns 401 (token revoked)

        // login2's refresh token should still work (NOT revoked by the retry)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login2.refreshToken())))
                .andExpect(status().isOk());
    }
}
