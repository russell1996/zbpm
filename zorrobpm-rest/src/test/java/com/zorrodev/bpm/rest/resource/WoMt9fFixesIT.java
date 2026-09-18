package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-9f: @Transactional on createApiKey + grant cascade on removeMember.
 * Full-context IT through real filter chain (V11).
 *
 * NOTE: Class-level @Transactional(NOT_SUPPORTED) disables Spring test's auto-transaction
 * wrapping so that @Transactional on production methods can be properly tested.
 * Without this, Spring test wraps each test in a transaction that masks missing @Transactional
 * on the SUT — the classic "test passes, prod 500" trap (CTO lesson).
 *
 * Covers criteria #1-#4 from WO-MT-9f.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WoMt9fFixesIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessMemberRepository processMemberRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyGrantRepository apiKeyGrantRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;
    private UUID superAdminId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
        superAdminId = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole())).findFirst().orElseThrow().getId();

        processKey = deployProcess("mt9f-proc");
    }

    // ==================== Criterion #1: revoke → create → 200 (not 500) ====================

    /**
     * Proof-of-failure (V3): revoke-then-create on a fresh user.
     *
     * RED (before @Transactional fix): createApiKey does delete+save without @Transactional
     *   → 500 "No EntityManager with actual transaction available"
     * GREEN (after @Transactional): createApiKey is @Transactional → 200, new secret returned.
     *
     * Uses POST /me/api-key/revoke (user self-service) to revoke, then
     * POST /admin/users/{id}/api-key to create a new key.
     */
    @Test
    void revokeThenCreate_returnsNewKey() throws Exception {
        UUID testUserId = createUser("mt9f-revoke-create", "USER");
        addMember(testUserId, processKey, "OWNER");

        // Create key as super-admin
        MvcResult createResult = mockMvc.perform(post("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isCreated())
            .andReturn();
        String firstKey = mapper.readTree(createResult.getResponse().getContentAsString()).get("key").asText();

        // Login as user to use self-service revoke
        String userToken = loginAndGetToken("mt9f-revoke-create", "pass");

        // Revoke via POST /me/api-key/revoke (user self-service)
        mockMvc.perform(post("/me/api-key/revoke")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isOk());

        // Verify revoked via admin GET
        mockMvc.perform(get("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        // Create again → 200 (NOT 500!) — this is the bug fix
        MvcResult recreateResult = mockMvc.perform(post("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isCreated())
            .andReturn();
        String secondKey = mapper.readTree(recreateResult.getResponse().getContentAsString()).get("key").asText();
        assertNotNull(secondKey, "New key must be returned");
        assertTrue(secondKey.startsWith("zbpm_sk_"), "New key must have correct prefix");
        assertNotEquals(firstKey, secondKey, "New key must differ from old");

        // Old key dead
        mockMvc.perform(get("/process-instances")
                .header("Authorization", "Bearer " + firstKey))
            .andExpect(status().isUnauthorized());

        // New key works
        mockMvc.perform(get("/process-instances")
                .header("Authorization", "Bearer " + secondKey))
            .andExpect(status().isOk());
    }

    // ==================== Criterion #2: removeMember cascades grant deletion ====================

    /**
     * Proof-of-failure (V3): after removeMember, grant for this process must be gone.
     *
     * RED (before fix): removeMember deletes membership but NOT the api_key_grant
     *   → GET /admin/users/{id}/api-key still shows grant for the removed process.
     * GREEN (after fix): removeMember cascades to api_key_grant deletion
     *   → GET /admin/users/{id}/api-key no longer shows grant for the removed process.
     */
    @Test
    void removeMember_cascadesGrantDeletion() throws Exception {
        UUID testUserId = createUser("mt9f-cascade", "USER");
        addMember(testUserId, processKey, "OWNER");

        // Create key + set grant for this process
        createApiKeyForUser(testUserId);
        setGrants(testUserId, processKey, "START");

        // Verify grant exists
        MvcResult getKeyBefore = mockMvc.perform(get("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        String bodyBefore = getKeyBefore.getResponse().getContentAsString();
        assertTrue(bodyBefore.contains(processKey), "Grant for process must exist before remove");

        // Need a second process so we can remove membership without hitting "last OWNER" constraint
        String process2Key = deployProcess("mt9f-proc2");
        addMember(testUserId, process2Key, "OWNER");

        // Set grant for both processes
        setGrants(testUserId, processKey, "START", process2Key, "START");

        // Remove membership from first process
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + testUserId)
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Verify grant for first process is GONE
        MvcResult getKeyAfter = mockMvc.perform(get("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        String bodyAfter = getKeyAfter.getResponse().getContentAsString();
        assertFalse(bodyAfter.contains("\"processKey\":\"" + processKey + "\""),
            "Grant for removed process must be deleted");
        assertTrue(bodyAfter.contains(process2Key),
            "Grant for other process must remain");
    }

    // ==================== Criterion #3: key cannot access process after member remove ====================

    /**
     * Proof-of-failure (V3): API key START after membership removal → 403.
     *
     * RED (before fix): grant persists after removeMember → key can START → 200 (security hole).
     * GREEN (after fix): grant is cascaded → key START → 403.
     */
    @Test
    void keyCannotAccessProcessAfterMemberRemove() throws Exception {
        UUID testUserId = createUser("mt9f-access-deny", "USER");
        addMember(testUserId, processKey, "OWNER");

        // Create key + grant for START on this process
        String apiKey = createApiKeyForUser(testUserId);
        setGrants(testUserId, processKey, "START");

        // Verify key works BEFORE removal
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + apiKey)
                .content("{\"processDefinitionKey\":\"" + processKey + "\",\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());

        // Need a second process for remove (avoid last OWNER constraint)
        String process2Key = deployProcess("mt9f-proc2");
        addMember(testUserId, process2Key, "OWNER");
        setGrants(testUserId, processKey, "START", process2Key, "START");

        // Remove membership from first process
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + testUserId)
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Key cannot START on removed process → 403
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + apiKey)
                .content("{\"processDefinitionKey\":\"" + processKey + "\",\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());
    }

    // ==================== Criterion #4: revoke writes KEY_REVOKE audit; remove writes MEMBER_REMOVE audit ====================

    /**
     * Audit completeness: revoke → KEY_REVOKE in audit_log; removeMember → MEMBER_REMOVE in audit_log.
     */
    @Test
    void revokeWritesAuditAndRemoveCascadeWritesAudit() throws Exception {
        UUID testUserId = createUser("mt9f-audit", "USER");
        addMember(testUserId, processKey, "OWNER");

        // Record count before
        long countBefore = auditLogRepository.count();

        // Create key + grant
        createApiKeyForUser(testUserId);
        setGrants(testUserId, processKey, "START");

        // Revoke via admin endpoint
        mockMvc.perform(post("/admin/users/" + testUserId + "/api-key/revoke")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Verify KEY_REVOKE audit entry
        var revokeEntries = auditLogRepository.findByFilters(null, null, null, null);
        boolean hasKeyRevoke = revokeEntries.stream()
            .anyMatch(e -> "KEY_REVOKE".equals(e.getAction())
                && testUserId.toString().equals(e.getTargetId()));
        assertTrue(hasKeyRevoke, "KEY_REVOKE audit entry must exist for this user");

        // Need a second process for remove
        String process2Key = deployProcess("mt9f-proc-audit");
        addMember(testUserId, process2Key, "OWNER");

        // Remove membership
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + testUserId)
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Verify MEMBER_REMOVE audit entry
        var removeEntries = auditLogRepository.findByFilters(null, null, null, null);
        boolean hasMemberRemove = removeEntries.stream()
            .anyMatch(e -> "MEMBER_REMOVE".equals(e.getAction())
                && testUserId.toString().equals(e.getTargetId()));
        assertTrue(hasMemberRemove, "MEMBER_REMOVE audit entry must exist for this user");
    }

    // ==================== Helpers ====================

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
        return userRepository.save(user).getId();
    }

    private String deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return key;
    }

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrants(UUID userId, String... pairs) throws Exception {
        StringBuilder sb = new StringBuilder("{\"grants\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("{\"processKey\":\"").append(pairs[i]).append("\",\"permissions\":\"").append(pairs[i + 1]).append("\"}");
        }
        sb.append("]}");
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(sb.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
