package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-63: access-token revocation via token_version.
 *
 * <p>PoC from the audit: copy the access token → logout → the copied token must
 * die (before the fix, {@code GET /auth/me} still returned 200). Same guarantee
 * for password change, deactivation and role demotion (F01). Every test uses its
 * OWN dedicated user (P-8: no seeded-admin mutation, no cross-test coupling).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessTokenRevocationIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired StubMailSender stubMail;
    @Autowired com.zorrodev.bpm.engine.repository.PasswordTokenRepository tokenRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String PASS = "OldPassw0rd!secure";
    private static final String NEW_PASS = "NewPassw0rd!secure2";

    private UUID createUser(String prefix, String role) {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername(prefix + "-" + id.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash(PASS));
        u.setFullName(prefix + " User");
        u.setEmail(prefix + "-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole(role);
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        return id;
    }

    private String loginToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private String usernameOf(UUID id) {
        return userRepository.findById(id).orElseThrow().getUsername();
    }

    // ==================== Criterion 1: logout revokes the copied token ====================

    @Test
    void logoutRevokesCopiedAccessToken() throws Exception {
        UUID id = createUser("revoke-logout", "USER");
        String token = loginToken(usernameOf(id), PASS);

        // sanity: fresh token works
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        // the COPIED token must now be dead — 401, not 200
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion 2: password change revokes the old token ====================

    @Test
    void passwordChangeRevokesOldAccessToken() throws Exception {
        UUID id = createUser("revoke-passwd", "USER");
        String oldToken = loginToken(usernameOf(id), PASS);

        String body = "{\"currentPassword\":\"" + PASS + "\",\"newPassword\":\"" + NEW_PASS + "\"}";
        mockMvc.perform(put("/me/password")
                .header("Authorization", "Bearer " + oldToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // old token dead
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());

        // new password logs in fine
        String fresh = loginToken(usernameOf(id), NEW_PASS);
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + fresh))
            .andExpect(status().isOk());
    }

    // ==================== Criterion 3: one user's logout does not kill others ====================

    @Test
    void logoutDoesNotRevokeOtherUsersTokens() throws Exception {
        UUID a = createUser("revoke-other-a", "USER");
        UUID b = createUser("revoke-other-b", "USER");
        String tokenA = loginToken(usernameOf(a), PASS);
        String tokenB = loginToken(usernameOf(b), PASS);

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isOk());
    }

    // ==================== F01: deactivation kills the token ====================

    @Test
    void deactivatedUserTokenDies() throws Exception {
        UUID id = createUser("revoke-deact", "USER");
        String token = loginToken(usernameOf(id), PASS);

        String adminToken = loginToken("admin", "admin");
        mockMvc.perform(put("/users/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"active\":false}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    // ==================== F01: demotion kills the stale role ====================

    @Test
    void demotedSuperAdminLosesStaleRole() throws Exception {
        UUID id = createUser("revoke-demote", "SUPER_ADMIN");
        String staleToken = loginToken(usernameOf(id), PASS);

        String adminToken = loginToken("admin", "admin");
        mockMvc.perform(put("/users/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"role\":\"USER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // SUPER_ADMIN-only endpoint with the stale SUPER_ADMIN token: 401, not 200/400.
        // (200 would mean the stale role is still honored; 400 would mean it reached
        // the controller, i.e. the filter still accepted it.)
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + staleToken)
                .content("{\"bpmn\":\"\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ==================== F02: refresh sets the access cookie ====================

    @Test
    void refreshSetsAccessCookieWithSameFlags() throws Exception {
        UUID id = createUser("revoke-refresh", "USER");
        String username = usernameOf(id);

        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        MvcResult loginResult = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        // WO-SEC-64: access cookie is __Host- since SEC-64 (Secure+Path=/ enforced
        // browser-side); legacy name no longer set, only cleared on logout.
        Cookie loginAccess = loginResult.getResponse().getCookie("__Host-zbpm_token");
        assertThat(loginAccess).as("login must set __Host-zbpm_token").isNotNull();
        Collection<String> loginHeaders = loginResult.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookie(loginHeaders, "refresh_token");
        assertThat(refreshToken).as("login must set refresh_token").isNotBlank();

        // ensure the re-issued token differs (issue() embeds per-second exp):
        // wait for the wall-clock second to tick over — the real condition
        // the old fixed 1100ms sleep was approximating (WO-OPS-14).
        long beforeSecond = Instant.now().getEpochSecond();
        await().atMost(java.time.Duration.ofSeconds(5))
            .until(() -> Instant.now().getEpochSecond() != beforeSecond);

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                // WO-SEC-70: this test asserts the JSON body carries the same token
                .header("X-Auth-Transport", "bearer")
                .cookie(new Cookie("refresh_token", refreshToken)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie refreshAccess = refreshResult.getResponse().getCookie("__Host-zbpm_token");
        assertThat(refreshAccess).as("refresh must set __Host-zbpm_token cookie (F02)").isNotNull();
        assertThat(refreshAccess.getValue()).as("refreshed cookie must carry a fresh token").isNotBlank();
        assertThat(refreshAccess.getValue()).as("refreshed token must differ").isNotEqualTo(loginAccess.getValue());
        // byte-identical flags to login (F02): SPA relies on the cookie, not the JSON
        assertThat(refreshAccess.isHttpOnly()).isEqualTo(loginAccess.isHttpOnly());
        assertThat(refreshAccess.getSecure()).isEqualTo(loginAccess.getSecure());
        assertThat(refreshAccess.getPath()).isEqualTo(loginAccess.getPath());
        assertThat(refreshAccess.getAttribute("SameSite")).isEqualTo(loginAccess.getAttribute("SameSite"));
        assertThat(refreshAccess.getMaxAge()).isEqualTo(loginAccess.getMaxAge());
        // JSON body carries the same token as the cookie
        String jsonToken = mapper.readTree(refreshResult.getResponse().getContentAsString()).get("token").asText();
        assertThat(jsonToken).isEqualTo(refreshAccess.getValue());

        // and the refreshed cookie actually authenticates
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + refreshAccess.getValue()))
            .andExpect(status().isOk());
    }

    private String extractCookie(Collection<String> headers, String name) {
        for (String h : headers) {
            if (h.contains(name + "=")) {
                return h.split(name + "=")[1].split(";")[0];
            }
        }
        return null;
    }

    // ==================== F03: password recovery revokes live sessions ====================

    @Test
    void passwordRecoveryRevokesRefreshAndAccessTokens() throws Exception {
        UUID id = createUser("revoke-recover", "USER");
        String username = usernameOf(id);
        String accessBefore = loginToken(username, PASS);

        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        MvcResult loginResult = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        String refreshBefore = extractCookie(
            loginResult.getResponse().getHeaders("Set-Cookie"), "refresh_token");
        assertThat(refreshBefore).as("login must issue a refresh cookie").isNotBlank();

        // trigger a RESET token via the public enumeration-safe endpoint
        String email = userRepository.findById(id).orElseThrow().getEmail();
        mockMvc.perform(post("/auth/forgot-password")
                .content("{\"email\":\"" + email + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        String resetToken = stubMail.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        resetToken = resetToken.substring(resetToken.indexOf("token=") + "token=".length()).trim();

        String recoveredPass = "Recovered1!secure";
        mockMvc.perform(post("/auth/reset-password")
                .content("{\"token\":\"" + resetToken + "\",\"password\":\"" + recoveredPass + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // pre-recovery access token dead
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessBefore))
            .andExpect(status().isUnauthorized());
        // pre-recovery refresh token dead — cannot mint fresh access past the recovery
        mockMvc.perform(post("/auth/refresh")
                .cookie(new Cookie("refresh_token", refreshBefore)))
            .andExpect(status().isUnauthorized());
        // recovered password logs in fine
        String fresh = loginToken(username, recoveredPass);
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + fresh))
            .andExpect(status().isOk());
    }

    // ==================== F03: EMAIL_VERIFY token is not a password setter ====================

    @Test
    void emailVerifyTokenRejectedOnResetEndpoint() throws Exception {
        // EMAIL_VERIFY token seeded directly (setup only — the rejection itself goes through
        // the real HTTP password path). Registered users cannot log in before approval, so a
        // register-flow variant cannot assert "password unchanged" via login here.
        UUID id = createUser("revoke-ev", "USER");
        String rawVerify = "ev-raw-" + UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.PasswordTokenEntity ev = new com.zorrodev.bpm.engine.entity.PasswordTokenEntity();
        ev.setId(UUID.randomUUID());
        ev.setUserId(id);
        ev.setType("EMAIL_VERIFY");
        ev.setTokenHash(new com.zorrodev.bpm.engine.security.TokenService(
            "test-secret-0123456789abcdef-test", 30, "", new org.springframework.mock.env.MockEnvironment())
            .hashToken(rawVerify));
        ev.setEmail("x@example.com");
        ev.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        ev.setUsed(false);
        ev.setCreatedAt(java.time.Instant.now());
        tokenRepository.save(ev);

        // EMAIL_VERIFY token on the password path: rejected, password untouched
        mockMvc.perform(post("/auth/reset-password")
                .content("{\"token\":\"" + rawVerify + "\",\"password\":\"Hacked1!secure\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());

        String stillGood = loginToken(usernameOf(id), PASS);
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + stillGood))
            .andExpect(status().isOk());
    }

    // ==================== F03: concurrent consume — exactly one success ====================

    @Test
    void concurrentResetConsume_exactlyOneSucceeds() throws Exception {
        UUID id = createUser("revoke-race", "USER");
        String email = userRepository.findById(id).orElseThrow().getEmail();
        mockMvc.perform(post("/auth/forgot-password")
                .content("{\"email\":\"" + email + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        String mailBody = stubMail.getSent().stream()
            .filter(mm -> email.equalsIgnoreCase(mm.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        String resetToken = mailBody.substring(mailBody.indexOf("token=") + "token=".length()).trim();

        String passA = "RaceA1!secure";
        String passB = "RaceB1!secure";
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<Integer> statuses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Runnable attempt = () -> {
            String pass = Thread.currentThread().getName().endsWith("0") ? passA : passB;
            try {
                ready.countDown();
                go.await();
                MvcResult r = mockMvc.perform(post("/auth/reset-password")
                        .content("{\"token\":\"" + resetToken + "\",\"password\":\"" + pass + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                    .andReturn();
                statuses.add(r.getResponse().getStatus());
            } catch (Exception e) {
                statuses.add(-1);
            }
        };
        Thread t1 = new Thread(attempt, "race-0");
        Thread t2 = new Thread(attempt, "race-1");
        t1.start();
        t2.start();
        assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        go.countDown();
        t1.join(30000);
        t2.join(30000);

        long okCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(okCount).as("exactly one concurrent consume must succeed, got " + statuses).isEqualTo(1);
        // exactly one of the two passwords is now live
        boolean aWorks;
        boolean bWorks;
        try {
            loginToken(usernameOf(id), passA);
            aWorks = true;
        } catch (AssertionError e) {
            aWorks = false;
        }
        try {
            loginToken(usernameOf(id), passB);
            bWorks = true;
        } catch (AssertionError e) {
            bWorks = false;
        }
        assertThat(aWorks ^ bWorks).as("exactly one raced password must be live").isTrue();
    }
}
