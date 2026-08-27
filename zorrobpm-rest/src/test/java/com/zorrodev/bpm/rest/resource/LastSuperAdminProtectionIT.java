package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-SEC-60: last SUPER_ADMIN protection.
 * Criterion 1: single SUPER_ADMIN cannot be demoted/deactivated → 409.
 * Criterion 2: with 2 SUPER_ADMIN, demotion succeeds.
 */
@ActiveProfiles("test")
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
    void setup() throws Exception {
        // Use the bootstrap admin (SUPER_ADMIN) — ensure we start from a known state.
        // The test DB is fresh per @SpringBootTest, so only the bootstrap admin exists initially.
        // Login as admin to get token and id.
        superAdminToken = loginAndGetToken("admin", "admin");
        // Find admin's id
        superAdminId = userRepository.findByUsername("admin").orElseThrow().getId();

        // Ensure admin is active SUPER_ADMIN (in case a previous test changed it)
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        // Remove any other SUPER_ADMIN that might have been left from a previous run
        // (keep the DB clean for criterion 1 which expects exactly 1)
        userRepository.findAll().stream()
                .filter(u -> !u.getId().equals(superAdminId) && "SUPER_ADMIN".equals(u.getRole()) && u.isActive())
                .forEach(u -> userRepository.delete(u));
        userRepository.flush();
    }

    @Test
    void criterion1_singleSuperAdmin_demoteToUser_isRejectedWith409() throws Exception {
        // Sanity: only one active SUPER_ADMIN at this point
        long count = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        assertEquals(1, count, "Precondition: exactly one active SUPER_ADMIN");

        // Try to demote the sole SUPER_ADMIN to USER
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

        // Verify DB still has SUPER_ADMIN
        UiUserEntity after = userRepository.findById(superAdminId).orElseThrow();
        assertEquals("SUPER_ADMIN", after.getRole(), "Role must remain SUPER_ADMIN");
        assertTrue(after.isActive(), "Active must remain true");
    }

    @Test
    void criterion1_singleSuperAdmin_deactivate_isRejectedWith409() throws Exception {
        long count = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        // If previous test left DB dirty, reset
        if (count != 1) {
            UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
            admin.setRole("SUPER_ADMIN");
            admin.setActive(true);
            userRepository.saveAndFlush(admin);
            userRepository.flush();
            // delete others
            userRepository.findAll().stream()
                    .filter(u -> !u.getId().equals(superAdminId) && "SUPER_ADMIN".equals(u.getRole()) && u.isActive())
                    .forEach(u -> userRepository.delete(u));
            userRepository.flush();
            count = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        }
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
        // Create a second SUPER_ADMIN
        UUID secondId = createUser("sec60-second", "SUPER_ADMIN");
        // Also ensure admin is still SUPER_ADMIN/active
        UiUserEntity admin = userRepository.findById(superAdminId).orElseThrow();
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        userRepository.saveAndFlush(admin);
        userRepository.flush();

        long countBefore = userRepository.countByRoleAndActive("SUPER_ADMIN", true);
        assertEquals(2, countBefore, "Precondition: two active SUPER_ADMIN");

        // Demote the second one to USER — should succeed
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

        // Now demote admin himself — should still succeed because one SUPER_ADMIN remains (the first was demoted, but admin is still SUPER_ADMIN)
        // Actually after demoting second, we have 1 SUPER_ADMIN left (admin). Demoting admin now would be last, so should be rejected.
        // Instead, test demoting admin when second still SUPER_ADMIN — need to reset second to SUPER_ADMIN first
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

    // --- helpers ---

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        UUID id = userRepository.saveAndFlush(user).getId();
        userRepository.flush();
        return id;
    }
}
