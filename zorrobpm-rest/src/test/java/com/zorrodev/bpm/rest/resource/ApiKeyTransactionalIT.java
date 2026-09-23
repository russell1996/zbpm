package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-BE-10: @Transactional on rotateApiKey/revokeApiKey.
 * Proves that audit failure rolls back repository save.
 *
 * POF GREEN (with @Transactional): mock auditLogService.record() throws RuntimeException
 *   → transaction rolled back → key NOT revoked/rotated.
 * POF RED (without @Transactional): same exception → save already committed → key IS revoked/rotated.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyTransactionalIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired ApiKeyRepository apiKeyRepository;

    @MockitoBean AuditLogService auditLogService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private UUID testUserId;
    private UUID apiKeyId;
    private String originalKeyHash;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        // Create user directly in DB (bypass resource to avoid mock interference)
        testUserId = UUID.randomUUID();
        UiUserEntity user = new UiUserEntity();
        user.setId(testUserId);
        user.setUsername("tx-test-" + testUserId.toString().substring(0, 8));
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("TX Test User");
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Create API key directly in DB (bypass resource to avoid mock)
        apiKeyId = UUID.randomUUID();
        originalKeyHash = KeyHasher.sha256("zbpm_sk_test_original_" + UUID.randomUUID());
        String prefix = "zbpm_sk_test_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO api_key (id, owner_user_id, key_hash, prefix, created_at) VALUES (?, ?, ?, ?, ?)",
            apiKeyId, testUserId, originalKeyHash, prefix, Instant.now());
    }

    // ==================== Criterion #2: audit failure rolls back revoke ====================

    /**
     * POF GREEN (with @Transactional): audit throws → tx rolls back → key NOT revoked.
     */
    @Test
    void revokeApiKey_auditFails_keyNotRevoked() throws Exception {
        // Mock audit to throw
        doThrow(new RuntimeException("audit write failed"))
            .when(auditLogService).record(any(), any(), any(), any());

        // Try to revoke → 500 (audit exception propagates)
        mockMvc.perform(post("/admin/users/" + testUserId + "/api-key/revoke")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().is5xxServerError());

        // Verify: key NOT revoked (tx rolled back)
        Integer revokedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM api_key WHERE id = ? AND revoked_at IS NOT NULL",
            Integer.class, apiKeyId);
        assertEquals(0, revokedCount, "Key must NOT be revoked when audit fails (tx rolled back)");
    }

    // ==================== Criterion #2: audit failure rolls back rotate ====================

    /**
     * POF GREEN (with @Transactional): audit throws → tx rolls back → key hash unchanged.
     */
    @Test
    void rotateApiKey_auditFails_keyNotRotated() throws Exception {
        // Mock audit to throw
        doThrow(new RuntimeException("audit write failed"))
            .when(auditLogService).record(any(), any(), any(), any());

        // Try to rotate → 500
        mockMvc.perform(post("/admin/users/" + testUserId + "/api-key/rotate")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().is5xxServerError());

        // Verify: key_hash unchanged (tx rolled back)
        String currentHash = jdbc.queryForObject(
            "SELECT key_hash FROM api_key WHERE id = ?",
            String.class, apiKeyId);
        assertEquals(originalKeyHash, currentHash, "Key hash must NOT change when audit fails (tx rolled back)");
    }

    // ==================== Criterion #3: happy-path still works ====================

    /**
     * Regression: revoke works when audit succeeds.
     */
    @Test
    void revokeApiKey_happyPath_keyRevoked() throws Exception {
        // Use a fresh user/key for happy-path test
        UUID freshUserId = UUID.randomUUID();
        UiUserEntity freshUser = new UiUserEntity();
        freshUser.setId(freshUserId);
        freshUser.setUsername("happy-" + freshUserId.toString().substring(0, 8));
        freshUser.setPasswordHash(passwordHasher.hash("pass"));
        freshUser.setFullName("Happy Path User");
        freshUser.setRole("USER");
        freshUser.setActive(true);
        freshUser.setCreatedAt(Instant.now());
        freshUser.setUpdatedAt(Instant.now());
        userRepository.save(freshUser);

        UUID freshKeyId = UUID.randomUUID();
        String freshKeyHash = KeyHasher.sha256("zbpm_sk_happy_" + UUID.randomUUID());
        String freshPrefix = "zbpm_sk_happy_" + freshUserId.toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO api_key (id, owner_user_id, key_hash, prefix, created_at) VALUES (?, ?, ?, ?, ?)",
            freshKeyId, freshUserId, freshKeyHash, freshPrefix, Instant.now());

        // Audit succeeds (no mock setup — mock is lenient by default, returns void)
        mockMvc.perform(post("/admin/users/" + freshUserId + "/api-key/revoke")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // Verify: key IS revoked
        Integer revokedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM api_key WHERE id = ? AND revoked_at IS NOT NULL",
            Integer.class, freshKeyId);
        assertEquals(1, revokedCount, "Key must be revoked on happy-path");
    }

    /**
     * Regression: rotate works when audit succeeds.
     */
    @Test
    void rotateApiKey_happyPath_keyRotated() throws Exception {
        UUID freshUserId = UUID.randomUUID();
        UiUserEntity freshUser = new UiUserEntity();
        freshUser.setId(freshUserId);
        freshUser.setUsername("rotate-" + freshUserId.toString().substring(0, 8));
        freshUser.setPasswordHash(passwordHasher.hash("pass"));
        freshUser.setFullName("Rotate User");
        freshUser.setRole("USER");
        freshUser.setActive(true);
        freshUser.setCreatedAt(Instant.now());
        freshUser.setUpdatedAt(Instant.now());
        userRepository.save(freshUser);

        UUID freshKeyId = UUID.randomUUID();
        String freshKeyHash = KeyHasher.sha256("zbpm_sk_rotate_" + UUID.randomUUID());
        String freshPrefix = "zbpm_sk_rotate_" + freshUserId.toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO api_key (id, owner_user_id, key_hash, prefix, created_at) VALUES (?, ?, ?, ?, ?)",
            freshKeyId, freshUserId, freshKeyHash, freshPrefix, Instant.now());

        // Rotate (audit succeeds — mock returns void by default)
        MvcResult result = mockMvc.perform(post("/admin/users/" + freshUserId + "/api-key/rotate")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();

        // Key hash changed
        String newHash = jdbc.queryForObject(
            "SELECT key_hash FROM api_key WHERE id = ?",
            String.class, freshKeyId);
        assertNotEquals(freshKeyHash, newHash, "Key hash must change after rotate");
    }

    // ==================== Helpers ====================

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType("application/json"))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
