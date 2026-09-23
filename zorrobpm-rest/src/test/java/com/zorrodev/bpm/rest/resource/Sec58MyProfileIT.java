package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-58 criteria 1-6: self-service password change via PUT /me/password.
 * Full-context IT (V11): real filter chain with a REAL JWT per user — the rate
 * limit runs BEFORE auth (brute force on the current password hits 429), and
 * auth runs before the resource (a stolen session still needs the current
 * password). Every test uses its OWN user and its OWN client IPs: buckets are
 * per-IP and shared in-JVM, so isolation must not depend on execution order.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=60",
})
class Sec58MyProfileIT {

    private static final String OLD_PASS = "OldPassw0rd!secure";
    private static final String NEW_PASS = "NewPassw0rd!secure2";

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final AtomicInteger ipSeq = new AtomicInteger(100);

    private record User(UUID id, String username, String password) {}

    private User newUser(boolean forceChange) {
        String username = "sec58-" + UUID.randomUUID().toString().substring(0, 8);
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(OLD_PASS));
        user.setFullName("Sec58 User");
        user.setEmail("sec58@example.com");
        user.setRole("USER");
        user.setUserType("HUMAN");
        user.setForcePasswordChange(forceChange);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return new User(user.getId(), username, OLD_PASS);
    }

    /** Real login → JWT. Unique IP per call so login buckets never collide. */
    private String loginToken(String ip, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private int loginStatus(String ip, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getStatus();
    }

    private MvcResult putMePassword(String ip, String token, String current, String next) throws Exception {
        String body = "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
        var b = put("/me/password").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            b = b.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(b).andReturn();
    }

    private String nextIp() { return "10.77." + (ipSeq.incrementAndGet() % 250) + "." + ipSeq.get(); }

    // ==================== Criterion 1 ====================

    @Test
    void criterion1_changeOwnPassword_newLoginWorks_oldLoginFails() throws Exception {
        User u = newUser(false);
        String token = loginToken(nextIp(), u.username(), u.password());

        assertThat(putMePassword(nextIp(), token, u.password(), NEW_PASS)
            .getResponse().getStatus()).isEqualTo(200);

        assertThat(loginStatus(nextIp(), u.username(), u.password()))
            .as("old password must stop working").isEqualTo(401);
        assertThat(loginStatus(nextIp(), u.username(), NEW_PASS))
            .as("login by the new password works").isEqualTo(200);
    }

    // ==================== Criterion 2 ====================

    @Test
    void criterion2_wrongCurrentPassword_refused_passwordUnchanged() throws Exception {
        User u = newUser(false);
        String token = loginToken(nextIp(), u.username(), u.password());

        MvcResult r = putMePassword(nextIp(), token, "WRONG-current-1", "Whatever!2345678");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("Current password");

        assertThat(loginStatus(nextIp(), u.username(), u.password()))
            .as("password unchanged after a refused attempt").isEqualTo(200);
    }

    // ==================== Criterion 3 ====================

    @Test
    void criterion3_cannotChangeRole_active_orOtherUser_viaThisPath() throws Exception {
        User u = newUser(false);
        String token = loginToken(nextIp(), u.username(), u.password());

        // victim account: another user whose row must stay untouched
        String victimName = "sec58-victim-" + UUID.randomUUID().toString().substring(0, 6);
        UiUserEntity victim = new UiUserEntity();
        victim.setId(UUID.randomUUID());
        victim.setUsername(victimName);
        victim.setPasswordHash(passwordHasher.hash("VictimPass!123"));
        victim.setFullName("Victim");
        victim.setRole("USER");
        victim.setUserType("HUMAN");
        victim.setActive(true);
        victim.setCreatedAt(Instant.now());
        victim.setUpdatedAt(Instant.now());
        userRepository.save(victim);

        // extra fields (role/active/userId) are NOT in ChangeMyPasswordDTO — ignored server-side
        String body = "{\"currentPassword\":\"" + u.password() + "\",\"newPassword\":\"RoleEsc!2345678\","
            + "\"role\":\"SUPER_ADMIN\",\"active\":false,\"userId\":\"" + victim.getId() + "\"}";
        MvcResult r = mockMvc.perform(put("/me/password").with(rr -> { rr.setRemoteAddr(nextIp()); return rr; })
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        UiUserEntity self = userRepository.findById(u.id()).orElseThrow();
        assertThat(self.getRole()).as("role must not be changeable via /me/password").isEqualTo("USER");

        UiUserEntity v = userRepository.findById(victim.getId()).orElseThrow();
        assertThat(v.getUsername()).as("victim account untouched").isEqualTo(victimName);
        assertThat(passwordHasher.matches("VictimPass!123", v.getPasswordHash()))
            .as("victim password untouched").isTrue();
    }

    // ==================== Criterion 4 ====================

    @Test
    void criterion4_successfulChange_clearsForcePasswordChange() throws Exception {
        User u = newUser(true); // locked-out scenario from the WO preamble
        String token = loginToken(nextIp(), u.username(), u.password());

        MvcResult r = putMePassword(nextIp(), token, u.password(), "ForceCleared!234");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        UiUserEntity after = userRepository.findById(u.id()).orElseThrow();
        assertThat(after.isForcePasswordChange())
            .as("successful self-change must clear forcePasswordChange")
            .isFalse();
    }

    // ==================== Criterion 5 (WO preamble check) ====================

    @Test
    void criterion5_usersCatalog_staysSuperAdminOnly_regularUser403() throws Exception {
        User u = newUser(false);
        String token = loginToken(nextIp(), u.username(), u.password());
        String ip = nextIp();

        // THE PREAMBLE PROOF: a REGULAR USER on /users gets 403 (SUPER_ADMIN guard) —
        // this is exactly why the old ChangePassword.vue flow was broken.
        MvcResult list = mockMvc.perform(get("/users")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andReturn();
        MvcResult upd = mockMvc.perform(put("/users/" + u.id())
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Whatever!2345678\"}"))
            .andExpect(status().isForbidden())
            .andReturn();
    }

    // ==================== Criterion 6 ====================

    @Test
    void criterion6_bruteForce_hitsPerUserBucket_sameIpBystanderUnaffected() throws Exception {
        // PROD TOPOLOGY (P-63 class): attacker and bystander share ONE address —
        // the external proxy's. Under the old clientIp-keyed bucket the bystander
        // was locked out by the attacker's flood; under the user-keyed bucket each
        // has an independent budget.
        User attacker = newUser(false);
        User bystander = newUser(false);
        String at = loginToken(nextIp(), attacker.username(), attacker.password());
        String bt = loginToken(nextIp(), bystander.username(), bystander.password());
        String sharedIp = nextIp();

        // capacity=5 (overridden): five wrong-current attempts by the ATTACKER → 400…
        for (int i = 0; i < 5; i++) {
            MvcResult r = putMePassword(sharedIp, at, "guess-" + i, "Xy9!aaaaBBBB");
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
        }
        // …the sixth request of the SAME USER is rate-limited BEFORE auth.
        MvcResult sixth = putMePassword(sharedIp, at, "guess-5", "Xy9!aaaaBBBB");
        assertThat(sixth.getResponse().getStatus()).isEqualTo(429);

        // THE HOLD-FIX PROOF: same shared IP, different authenticated user → own
        // bucket → NOT locked. This assertion REDs while the bucket is keyed on
        // clientIp and GREENs with the per-user key.
        MvcResult ok = putMePassword(sharedIp, bt, bystander.password(), "Xy9!aaaaBBBB");
        assertThat(ok.getResponse().getStatus())
            .as("another user behind the same proxy must keep own rate budget")
            .isEqualTo(200);
    }

    @Test
    void criterion6b_noValidToken_requests_stillCappedPerIp() throws Exception {
        // Anonymous garbage (no token) falls back to the per-IP key: first five pass
        // the limiter and die at authentication (401), the sixth is stopped at 429
        // BEFORE reaching the filter chain — the unauthenticated layer stays throttled.
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            assertThat(putMePassword(ip, null, "x", "Xy9!aaaaBBBB").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(putMePassword(ip, null, "x", "Xy9!aaaaBBBB").getResponse().getStatus())
            .as("anonymous flood on /me/password is still capped per-IP")
            .isEqualTo(429);
    }
}
