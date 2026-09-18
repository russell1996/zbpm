package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import jakarta.servlet.http.Cookie;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-55: refresh-token rotation race — two parallel /auth/refresh calls with the same
 * (still active) token must yield EXACTLY ONE success; the loser gets 401.
 *
 *  #1 (POF, V3): two REAL parallel threads (CountDownLatch), same token → exactly one 200, one 401
 *  #2: the winning successor token still works for the next refresh (regression)
 *  #3: grace-window retry (WO-SEC-18 L7) — covered by SecurityLowBatchIntegrationTest
 *      criterion3_retryRefreshDoesNotRevokeAllSessions
 *  #4: theft replay outside the grace window → reuse-detection revokes ALL user tokens (regression)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefreshTokenRaceIntegrationTest {

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

    private record LoginResult(String accessToken, String refreshToken) {}

    private LoginResult loginWithRefreshToken() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        Collection<String> headers = loginResult.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");
        return new LoginResult(auth.getToken(), refreshToken);
    }

    /** Fires two REAL parallel /auth/refresh calls with the same token. */
    private List<MvcResult> runTwoParallelRefreshes(String refreshToken) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<MvcResult> results = new CopyOnWriteArrayList<>();

        for (int t = 0; t < 2; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                    MvcResult r = mockMvc.perform(post("/auth/refresh")
                                    .cookie(new Cookie("refresh_token", refreshToken)))
                            .andReturn();
                    results.add(r);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }, "refresh-race-" + t);
            thread.start();
        }

        go.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS))
                .as("both racing threads must finish")
                .isTrue();
        return results;
    }

    // --- Criterion #1 (POF): parallel race → exactly one 200, one 401 ---

    @Test
    void criterion1_twoParallelRefreshes_sameToken_exactlyOneWins() throws Exception {
        // 3 fresh races to prove the outcome is deterministic, not a scheduling accident
        for (int i = 0; i < 3; i++) {
            LoginResult login = loginWithRefreshToken();
            assertThat(login.refreshToken()).isNotNull();

            List<MvcResult> results = runTwoParallelRefreshes(login.refreshToken());
            assertThat(results).hasSize(2);

            List<Integer> statuses = results.stream()
                    .map(r -> r.getResponse().getStatus())
                    .sorted()
                    .toList();
            assertThat(statuses)
                    .as("race #%d: exactly one refresh must win, the other must be rejected", i)
                    .containsExactly(200, 401);
        }
    }

    // --- Criterion #2: the winner's successor token keeps working (regression) ---

    @Test
    void criterion2_winningSuccessorToken_stillWorksForNextRefresh() throws Exception {
        LoginResult login = loginWithRefreshToken();
        assertThat(login.refreshToken()).isNotNull();

        List<MvcResult> results = runTwoParallelRefreshes(login.refreshToken());
        MvcResult winner = results.stream()
                .filter(r -> r.getResponse().getStatus() == 200)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 200 winner in the race"));

        String successor = extractCookieFromHeaders(winner.getResponse().getHeaders("Set-Cookie"), "refresh_token");
        assertThat(successor)
                .as("winner must have received a new refresh_token cookie")
                .isNotNull()
                .isNotBlank();

        // The successor must behave like a normal refresh token
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", successor)))
                .andExpect(status().isOk());
    }

    // --- Criterion #4: theft replay outside the 5s grace window → revokeAll (regression) ---

    @Test
    void criterion4_theftReplayOutsideGrace_revokesAllUserTokens() throws Exception {
        LoginResult login1 = loginWithRefreshToken();
        LoginResult login2 = loginWithRefreshToken();
        assertThat(login1.refreshToken()).isNotNull();
        assertThat(login2.refreshToken()).isNotNull();

        // Rotate login1's token (now revoked)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", login1.refreshToken())))
                .andExpect(status().isOk());

        // Leave the 5s grace window so the replay is classified as genuine theft
        Thread.sleep(6000);

        // Theft: replay the rotated token → 401 + revokeAll
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", login1.refreshToken())))
                .andExpect(status().isUnauthorized());

        // revokeAll fired → login2's token must be dead too
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", login2.refreshToken())))
                .andExpect(status().isUnauthorized());
    }
}
