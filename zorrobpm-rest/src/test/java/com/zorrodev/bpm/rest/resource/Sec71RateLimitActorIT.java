package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-71: actor identification in {@code RateLimitFilter} — full-context IT (V11),
 * real filter chain.
 *
 * <p>N04: {@code AuthResource} sets {@code __Host-zbpm_token}, but the filter resolved
 * the /me/password bucket from the legacy {@code zbpm_token} name only — every
 * current-cookie user shared one {@code me-password:anon} bucket with unauthenticated
 * traffic. S-RL-2: the login username was sliced out of the body with
 * {@code indexOf}/{@code substring}, so an escaped form ({@code "\u0073..."}) bucketed
 * separately from the plain name Jackson actually authenticates.
 *
 * <p>Bucket hygiene: {@code reset()} before AND after each test — the
 * {@code me-password:anon} bucket is global, so this class neither inherits nor
 * leaves consumption behind (shared in-JVM state with sibling IT classes).
 * Per-test users (random suffix) and IPs (10.71.x) are unique regardless.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=5",
})
class Sec71RateLimitActorIT {

    private static final String OLD_PASS = "OldPassw0rd!secure";
    private static final String NEW_PASS = "NewPassw0rd!secure2";

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final AtomicInteger ipSeq = new AtomicInteger(0);

    @BeforeEach
    void resetBefore() {
        rateLimitFilter.reset();
    }

    @AfterEach
    void resetAfter() {
        rateLimitFilter.reset();
    }

    private record User(UUID id, String username, String password) {}

    private User newUser() {
        String username = "sec71-" + UUID.randomUUID().toString().substring(0, 8);
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(OLD_PASS));
        user.setFullName("Sec71 User");
        user.setEmail("sec71@example.com");
        user.setRole("USER");
        user.setUserType("HUMAN");
        user.setForcePasswordChange(false);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return new User(user.getId(), username, OLD_PASS);
    }

    /** Real login → JSON token (no Sec-Fetch-Site → non-browser client → token present). */
    private String loginToken(String ip, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private int loginStatus(String ip, String body) throws Exception {
        return mockMvc.perform(post("/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus();
    }

    /**
     * PUT /me/password authenticated ONLY by the {@code __Host-} cookie (no Bearer
     * header — exactly the SPA transport). Mutating cookie-auth requests carry
     * Origin like the SPA does, otherwise CsrfFilter answers 403.
     */
    private int putMePasswordCookie(String ip, String cookieToken, String current, String next) throws Exception {
        String body = "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
        var b = put("/me/password").with(r -> { r.setRemoteAddr(ip); return r; })
            .header("Origin", "http://localhost")
            .contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookieToken != null) {
            b = b.cookie(new Cookie("__Host-zbpm_token", cookieToken));
        }
        return mockMvc.perform(b).andReturn().getResponse().getStatus();
    }

    private String nextIp() {
        int n = ipSeq.incrementAndGet();
        return "10.71." + (n / 250) + "." + (n % 250 + 1);
    }

    // ==================== Criterion 1 (N04) ====================

    @Test
    void criterion1_twoCookieUsers_haveIndependentBuckets() throws Exception {
        User attacker = newUser();
        User bystander = newUser();
        String at = loginToken(nextIp(), attacker.username(), attacker.password());
        String bt = loginToken(nextIp(), bystander.username(), bystander.password());
        String sharedIp = nextIp();

        // Attacker burns ONLY his own per-user bucket (5×400 wrong current password)…
        for (int i = 0; i < 5; i++) {
            assertThat(putMePasswordCookie(sharedIp, at, "guess-" + i, NEW_PASS)).isEqualTo(400);
        }
        assertThat(putMePasswordCookie(sharedIp, at, "guess-5", NEW_PASS))
            .as("6th request of the same cookie user → 429")
            .isEqualTo(429);

        // …while the bystander behind the SAME proxy address keeps his own budget.
        // RED pre-fix: both shared me-password:anon → 429 here.
        assertThat(putMePasswordCookie(sharedIp, bt, bystander.password(), NEW_PASS))
            .as("another __Host-cookie user behind the same proxy must keep own rate budget")
            .isEqualTo(200);
    }

    // ==================== Criterion 2 (N04) ====================

    @Test
    void criterion2_anonFlood_doesNotConsumeCookieUserBucket() throws Exception {
        User user = newUser();
        String token = loginToken(nextIp(), user.username(), user.password());
        String ip = nextIp();

        for (int i = 0; i < 5; i++) {
            assertThat(putMePasswordCookie(ip, null, "x", NEW_PASS)).isEqualTo(401);
        }
        assertThat(putMePasswordCookie(ip, null, "x", NEW_PASS))
            .as("anonymous flood is still capped")
            .isEqualTo(429);

        // RED pre-fix: the cookie user also landed in me-password:anon → 429 here.
        // Same address as the flood (shared-proxy topology): identity must come
        // from the cookie, not from the absence of one.
        assertThat(putMePasswordCookie(ip, token, user.password(), NEW_PASS))
            .as("authenticated __Host-cookie user unaffected by the anonymous flood")
            .isEqualTo(200);
    }

    // ==================== Criterion 3 (S-RL-2) ====================

    @Test
    void criterion3_escapedUsername_hitsSameAccountBucket() throws Exception {
        User u = newUser();
        // Same name as the backend will authenticate, but with the first char
        // unicode-escaped — Jackson decodes it to the identical string, while the
        // old substring scan bucketed the raw "\u0073..." slice separately.
        String escaped = "\\u0073" + u.username().substring(1);
        assertThat(escaped).isNotEqualTo(u.username());

        // 2 plain + 3 escaped wrong-password attempts = 5 on ONE account bucket
        // (distinct IPs so the per-IP bucket never trips).
        for (int i = 0; i < 2; i++) {
            String body = "{\"username\":\"" + u.username() + "\",\"password\":\"Wrong-1\"}";
            assertThat(loginStatus(nextIp(), body)).isEqualTo(401);
        }
        for (int i = 0; i < 3; i++) {
            String body = "{\"username\":\"" + escaped + "\",\"password\":\"Wrong-1\"}";
            assertThat(loginStatus(nextIp(), body))
                .as("escaped login must authenticate (proves decode == plain name)")
                .isEqualTo(401);
        }

        // 6th attempt — even with the CORRECT password — hits the exhausted
        // account bucket. RED pre-fix: escaped attempts went elsewhere → 200 here.
        String okBody = "{\"username\":\"" + u.username() + "\",\"password\":\"" + u.password() + "\"}";
        assertThat(loginStatus(nextIp(), okBody))
            .as("account bucket must count escaped and plain forms together")
            .isEqualTo(429);
    }
}
