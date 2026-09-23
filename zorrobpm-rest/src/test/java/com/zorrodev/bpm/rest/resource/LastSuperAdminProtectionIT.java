package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-SEC-60: last SUPER_ADMIN protection.
 * Criterion 1: single SUPER_ADMIN cannot be demoted/deactivated → 409.
 * Criterion 2: with 2 SUPER_ADMIN, demotion/deactivation succeeds.
 * Additional: SYSTEM + HUMAN case, normalizeRole, race.
 */
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:sec60isolated")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LastSuperAdminProtectionIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String superAdminToken;
    private UUID superAdminId;

    @BeforeAll
    void setup() {
        superAdminId = userRepository.findByUsername("admin").orElseThrow().getId();

        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();
        // Do NOT delete other SUPER_ADMINs via findAll — use unique names per test and clean only own creations
    }

    /**
     * Root cause (CI pipeline 169886, commit 019f93c3): the class cached
     * {@code superAdminToken} ONCE in {@code @BeforeAll}, but
     * {@code criterion2_twoSuperAdmins_demoteOne_succeeds} really demotes the admin himself
     * via {@code PUT /users/{superAdminId}} ("Now admin demotes himself"). Since WO-SEC-63
     * {@code UiUserServiceImpl.update()} bumps {@code tokenVersion} on any role/active change,
     * the DB version moves on while the cached JWT still carries the old {@code ver} claim —
     * and the test restores {@code role='SUPER_ADMIN'} via raw JPA ({@code saveAndFlush}),
     * bypassing the service so the version never rolls back. Every later test reusing the
     * stale token then gets 401 from {@code JwtAuthFilter} (version mismatch) instead of the
     * expected 409/200. The concurrent-race test demoting the admin via API bumps the version
     * the same way depending on method order. Re-login here gives every test a token minted
     * from the CURRENT {@code tokenVersion}, and the wipe of {@code sec60-*} leftovers makes
     * each precondition independent of whether a previous test failed before its end-of-test
     * cleanup (the old count=3-instead-of-2 failure).
     */
    @BeforeEach
    void resetAdminAndRefreshToken() throws Exception {
        // Idempotent per-test precondition: wipe leftovers of ANY previous test in this
        // class (any role — a failed test may leave its second user demoted to USER or
        // deactivated, which still pollutes later counts if only SUPER_ADMIN rows are wiped).
        userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null && u.getUsername().startsWith("sec60-"))
                .forEach(u -> { userRepository.delete(u); });
        userRepository.flush();
        // Restore admin through JPA, THEN mint a fresh token carrying the current version.
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();
        superAdminToken = loginAndGetToken("admin", "admin");
    }

    @Test
    void criterion1_singleSuperAdmin_demoteToUser_isRejectedWith409() throws Exception {
        // Ensure exactly one active HUMAN SUPER_ADMIN (admin) at this point — count via new method
        long count = countHumanSuperAdmins();
        // If there are extra HUMAN SUPER_ADMINs from a previous failed test, remove them by deleting our own second user if exists
        // But we use unique names per test, so just check count ==1 or greater; if >1, this test's precondition fails and we need to clean
        // For robustness, if count !=1, try to reset to 1 by demoting any extra (but we don't know their ids) — instead, just assert and if fails, the test will show the issue
        // Use the old count method for diagnostics as well
        long oldCount = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        // Ensure we have exactly 1 HUMAN SUPER_ADMIN by deleting any extra HUMAN SUPER_ADMIN we created in previous tests (by username prefix)
        // Our second user in criterion2 is "sec60-second-*" with unique suffix, so we can delete them
        userRepository.findAll().stream()
                .filter(u -> u.getUsername().startsWith("sec60-second-") && "SUPER_ADMIN".equals(u.getRole()))
                .forEach(u -> { userRepository.delete(u); userRepository.flush(); });
        userRepository.findAll().stream()
                .filter(u -> u.getUsername().startsWith("sec60-system-") && "SUPER_ADMIN".equals(u.getRole()))
                .forEach(u -> { userRepository.delete(u); userRepository.flush(); });
        // Also ensure admin is still SUPER_ADMIN/HUMAN/active
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        if (!"SUPER_ADMIN".equals(admin.getRole()) || !admin.isActive() || !"HUMAN".equals(admin.getUserType())) {
            admin.setRole("SUPER_ADMIN");
            admin.setActive(true);
            admin.setUserType("HUMAN");
            userRepository.saveAndFlush(admin);
            userRepository.flush();
        }
        count = countHumanSuperAdmins();
        assertEquals(1, count, "Precondition: exactly one active HUMAN SUPER_ADMIN");

        String body = """
                {"role":"USER"}
                """;
        MvcResult result = mockMvc.perform(put("/users/" + superAdminId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("last active SUPER_ADMIN") || response.contains("SUPER_ADMIN"),
                "Error message should mention SUPER_ADMIN, got: " + response);

        UiUserEntity after = userRepository.findById(superAdminId).orElseThrow();
        assertEquals("SUPER_ADMIN", after.getRole(), "Role must remain SUPER_ADMIN");
        assertTrue(after.isActive(), "Active must remain true");
    }

    @Test
    void criterion1_singleSuperAdmin_deactivate_isRejectedWith409() throws Exception {
        // Clean any extra from previous tests
        userRepository.findAll().stream()
                .filter(u -> u.getUsername().startsWith("sec60-") && "SUPER_ADMIN".equals(u.getRole()))
                .forEach(u -> { userRepository.delete(u); userRepository.flush(); });
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long count = countHumanSuperAdmins();
        assertEquals(1, count);

        String body = """
                {"active":false}
                """;
        mockMvc.perform(put("/users/" + superAdminId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());

        UiUserEntity after = userRepository.findById(superAdminId).orElseThrow();
        assertTrue(after.isActive(), "Active must remain true after rejected deactivation");
    }

    @Test
    void criterion2_twoSuperAdmins_demoteOne_succeeds() throws Exception {
        String secondName = "sec60-second-" + UUID.randomUUID().toString().substring(0, 8);
        UUID secondId = createUser(secondName, "SUPER_ADMIN", "HUMAN");
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long countBefore = countHumanSuperAdmins();
        assertEquals(2, countBefore, "Precondition: two active HUMAN SUPER_ADMIN");

        String body = """
                {"role":"USER"}
                """;
        mockMvc.perform(put("/users/" + secondId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        UiUserEntity secondAfter = userRepository.findById(secondId).orElseThrow();
        assertEquals("USER", secondAfter.getRole());

        // Reset second to SUPER_ADMIN for the self-demotion part
        secondAfter.setRole("SUPER_ADMIN");
        userRepository.saveAndFlush(secondAfter);
        userRepository.flush();

        // Now admin demotes himself — should succeed because second is still SUPER_ADMIN
        mockMvc.perform(put("/users/" + superAdminId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        UiUserEntity adminAfter = userRepository.findById(superAdminId).orElseThrow();
        assertEquals("USER", adminAfter.getRole());

        // Cleanup: restore admin to SUPER_ADMIN for other tests
        adminAfter.setRole("SUPER_ADMIN");
        userRepository.saveAndFlush(adminAfter);
        userRepository.flush();
        userRepository.deleteById(secondId);
        userRepository.flush();
    }

    @Test
    void criterion2_twoSuperAdmins_deactivateOne_succeeds() throws Exception {
        String secondName = "sec60-second-deact-" + UUID.randomUUID().toString().substring(0, 8);
        UUID secondId = createUser(secondName, "SUPER_ADMIN", "HUMAN");
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long countBefore = countHumanSuperAdmins();
        assertEquals(2, countBefore, "Precondition: two active HUMAN SUPER_ADMIN");

        String body = """
                {"active":false}
                """;
        mockMvc.perform(put("/users/" + secondId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        UiUserEntity secondAfter = userRepository.findById(secondId).orElseThrow();
        assertFalse(secondAfter.isActive(), "Second should be deactivated");

        // Cleanup
        userRepository.deleteById(secondId);
        userRepository.flush();
        // Ensure admin still active
        admin = userRepository.findById(superAdminId).orElseThrow();
        if (!admin.isActive()) {
            admin.setActive(true);
            userRepository.saveAndFlush(admin);
            userRepository.flush();
        }
    }

    @Test
    void systemSuperAdmin_isNotCountedAsHumanReserve() throws Exception {
        // State: 1 HUMAN SUPER_ADMIN (admin) + 1 SYSTEM SUPER_ADMIN
        String systemName = "sec60-system-" + UUID.randomUUID().toString().substring(0, 8);
        UUID systemId = createUser(systemName, "SUPER_ADMIN", "SYSTEM");
        // Ensure admin is HUMAN SUPER_ADMIN
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long humanCount = countHumanSuperAdmins();
        long oldCount = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        assertEquals(1, humanCount, "Precondition: exactly one HUMAN SUPER_ADMIN");
        assertEquals(2, oldCount, "Old count would be 2 (including SYSTEM), new should be 1");

        // Try to demote the sole HUMAN SUPER_ADMIN — should be rejected (409), even though old count would be 2
        String body = """
                {"role":"USER"}
                """;
        mockMvc.perform(put("/users/" + superAdminId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());

        UiUserEntity after = userRepository.findById(superAdminId).orElseThrow();
        assertEquals("SUPER_ADMIN", after.getRole(), "HUMAN SUPER_ADMIN must remain");

        // Cleanup
        userRepository.deleteById(systemId);
        userRepository.flush();
    }

    @Test
    void normalizeRole_keepsSuperAdminOnNonRoleUpdate() throws Exception {
        // Create a SUPER_ADMIN user
        String userName = "sec60-norm-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(userName, "SUPER_ADMIN", "HUMAN");

        // Update non-role field (fullName) while sending role=SUPER_ADMIN — should stay SUPER_ADMIN
        String body = """
                {"fullName":"new name", "role":"SUPER_ADMIN"}
                """;
        mockMvc.perform(put("/users/" + userId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        UiUserEntity after = userRepository.findById(userId).orElseThrow();
        assertEquals("SUPER_ADMIN", after.getRole(), "Role must remain SUPER_ADMIN after non-role update");
        assertEquals("new name", after.getFullName());

        // Also test that email update with SUPER_ADMIN role keeps it
        String body2 = """
                {"email":"new@corp.kz", "role":"SUPER_ADMIN"}
                """;
        mockMvc.perform(put("/users/" + userId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(body2)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        UiUserEntity after2 = userRepository.findById(userId).orElseThrow();
        assertEquals("SUPER_ADMIN", after2.getRole());

        // Cleanup
        userRepository.deleteById(userId);
        userRepository.flush();
    }

    @Test
    void concurrentDemote_lastTwoSuperAdmins_onlyOneSucceeds() throws Exception {
        // Need exactly 2 HUMAN SUPER_ADMINs: admin + second
        String secondName = "sec60-race-" + UUID.randomUUID().toString().substring(0, 8);
        UUID secondId = createUser(secondName, "SUPER_ADMIN", "HUMAN");
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long countBefore = countHumanSuperAdmins();
        assertEquals(2, countBefore, "Precondition: two active HUMAN SUPER_ADMIN for race");

        // Two concurrent demotions: one demotes admin, one demotes second
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Runnable demoteAdmin = () -> {
            try {
                startLatch.await();
                MvcResult r = mockMvc.perform(put("/users/" + superAdminId)
                                .header("Authorization", "Bearer " + superAdminToken)
                                .content("{\"role\":\"USER\"}")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();
                int status = r.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };
        Runnable demoteSecond = () -> {
            try {
                startLatch.await();
                MvcResult r = mockMvc.perform(put("/users/" + secondId)
                                .header("Authorization", "Bearer " + superAdminToken)
                                .content("{\"role\":\"USER\"}")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();
                int status = r.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(demoteAdmin);
        executor.submit(demoteSecond);
        // Start both at the same time
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Exactly one should succeed, one should be rejected (409)
        // Due to PESSIMISTIC_WRITE, the second transaction will wait for the first's lock and then see count=1 and be rejected
        assertEquals(1, successCount.get(), "Exactly one demotion should succeed, got success=" + successCount.get() + " conflict=" + conflictCount.get());
        assertEquals(1, conflictCount.get(), "Exactly one demotion should be rejected with 409");

        // Verify DB has exactly one SUPER_ADMIN left
        long countAfter = countHumanSuperAdmins();
        assertEquals(1, countAfter, "After race, exactly one SUPER_ADMIN should remain");

        // Cleanup: ensure admin is SUPER_ADMIN and delete second
        admin = userRepository.findById(superAdminId).orElseThrow();
        if (!"SUPER_ADMIN".equals(admin.getRole())) {
            admin.setRole("SUPER_ADMIN");
            userRepository.saveAndFlush(admin);
            userRepository.flush();
        }
        // Second might have been demoted to USER or still SUPER_ADMIN — delete it regardless
        try {
            userRepository.deleteById(secondId);
            userRepository.flush();
        } catch (Exception ignored) {}
        // Ensure admin is SUPER_ADMIN for other tests
        admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setUserType("HUMAN");
        userRepository.saveAndFlush(admin);
        userRepository.flush();
    }

    // --- helpers ---

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID createUser(String username, String role, String userType) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setUserType(userType);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        UUID id = userRepository.saveAndFlush(user).getId();
        userRepository.flush();
        return id;
    }

    private UUID createUser(String username, String role) {
        return createUser(username, role, "HUMAN");
    }

    private long countHumanSuperAdmins() {
        return userRepository.findAll().stream()
                .filter(u -> "SUPER_ADMIN".equals(u.getRole()) && u.isActive() && "HUMAN".equals(u.getUserType()))
                .count();
    }
}
