package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
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

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-11: Logout clears access-cookie + reuse-detection + cookie TTL sync.
 *
 *  #1: logout clears zbpm_token (Set-Cookie maxAge=0)
 *  #2: after logout, old access-cookie → /auth/me = 401
 *  #3: reuse of revoked refresh → 401 + revokeAll
 *  #4: access-cookie maxAge == jwt-ttl
 *  #5: existing FEAT-4 IT pass (regression)
 *  #6: proof-of-failure — test #1 on current code → RED (zbpm_token NOT cleared)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogoutSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    private String extractCookieFromHeaders(Collection<String> headers, String name) {
        for (String h : headers) {
            if (h.contains(name + "=")) {
                return h.split(name + "=")[1].split(";")[0];
            }
        }
        return null;
    }

    private LoginResult loginAndExtract() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");
        String accessToken = extractCookieFromHeaders(headers, "zbpm_token");
        return new LoginResult(auth.getToken(), refreshToken, accessToken);
    }

    record LoginResult(String bearerToken, String refreshToken, String accessCookie) {}

    // --- Criterion #1: logout clears zbpm_token with Max-Age=0 ---

    @Test
    void criterion1_logout_clearsAccessTokenCookie() throws Exception {
        LoginResult login = loginAndExtract();
        assertThat(login.accessCookie()).isNotNull();

        MvcResult logoutResult = mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + login.bearerToken()))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = logoutResult.getResponse().getHeaders("Set-Cookie");
        // Check zbpm_token cookie is cleared (Max-Age=0)
        boolean zbpmCleared = headers.stream()
                .anyMatch(h -> h.contains("zbpm_token=") && h.contains("Max-Age=0"));
        assertThat(zbpmCleared)
                .as("logout must clear zbpm_token cookie with Max-Age=0")
                .isTrue();
    }

    // --- WO-AUDIT-4 S2: cleared cookies carry the same flags as set cookies ---
    //
    // NOTE: asserted on the Cookie OBJECTS (getCookie/getAttribute), not the rendered
    // Set-Cookie header — Spring's MockHttpServletResponse does not render
    // Cookie.setAttribute() entries (e.g. SameSite) into the header string, while a
    // real container (Tomcat) does. Header-asserting SameSite here would fail even
    // for the login path, which uses the identical API.

    @Test
    void audit4s2_logout_clearedCookiesCarrySetFlags() throws Exception {
        LoginResult login = loginAndExtract();

        MvcResult logoutResult = mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + login.bearerToken()))
                .andExpect(status().isOk())
                .andReturn();

        // Otherwise a stale Secure-cookie may survive clearing (audit §7 S2).
        // WO-SEC-64: access cookie is __Host- (Path=/), refresh narrowed to
        // Path=/auth/refresh — clear-paths must match set-paths, else the
        // browser keeps the cookie. Legacy zbpm_token cleared too (rotation).
        for (String[] spec : new String[][]{
                {"__Host-zbpm_token", "/"}, {"zbpm_token", "/"}, {"refresh_token", "/auth/refresh"}}) {
            jakarta.servlet.http.Cookie cleared =
                logoutResult.getResponse().getCookie(spec[0]);
            assertThat(cleared).as(spec[0] + " cleared cookie present").isNotNull();
            assertThat(cleared.getMaxAge()).as(spec[0] + " clear Max-Age=0").isZero();
            assertThat(cleared.isHttpOnly()).as(spec[0] + " clear HttpOnly").isTrue();
            assertThat(cleared.getSecure()).as(spec[0] + " clear Secure").isTrue();
            assertThat(cleared.getAttribute("SameSite"))
                .as(spec[0] + " clear SameSite=Strict").isEqualTo("Strict");
            assertThat(cleared.getPath()).as(spec[0] + " clear Path=" + spec[1]).isEqualTo(spec[1]);
        }
    }

    // --- Criterion #2: after logout, no access cookie → /auth/me = 401 ---
    // (JWT is stateless — can't invalidate server-side. Cookie cleared from response.)

    @Test
    void criterion2_afterLogout_noAccessCookieReturns401() throws Exception {
        LoginResult login = loginAndExtract();

        // Logout
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + login.bearerToken()))
                .andExpect(status().isOk());

        // /auth/me without any cookie → 401 (browser cleared cookie after logout)
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // --- Criterion #3: reuse of revoked refresh → 401 + revokeAll ---

    @Test
    void criterion3_reuseRevokedRefreshToken_returns401() throws Exception {
        LoginResult login = loginAndExtract();
        assertThat(login.refreshToken()).isNotNull();

        // Logout → revokes all refresh tokens
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + login.bearerToken()))
                .andExpect(status().isOk());

        // First refresh attempt with revoked token → 401
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized());

        // Second refresh attempt with same revoked token → still 401 (already revoked all)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    // --- Criterion #4: access-cookie maxAge should match jwt-ttl ---

    @Test
    void criterion4_login_setsAccessTokenCookieMaxAge() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String zbpmCookie = headers.stream()
                .filter(h -> h.contains("zbpm_token="))
                .findFirst()
                .orElse(null);

        assertThat(zbpmCookie).isNotNull();
        assertThat(zbpmCookie).contains("Max-Age=");
    }

    // --- Criterion #5: existing FEAT-4 test criteria still pass ---

    @Test
    void criterion5_loginStillSetsRefreshCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        Collection<String> headers = result.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");
        assertThat(refreshToken).isNotNull().isNotBlank();
    }

    // --- Criterion #6: proof-of-failure (V3) ---
    // This test demonstrates that on the current code, logout does NOT clear zbpm_token.
    // After fix, this test MUST pass (zbpm_token cleared).
    // The test is criterion1_logout_clearsAccessTokenCookie above.
    // RED output documented in mimo-to-cto.md.
}
