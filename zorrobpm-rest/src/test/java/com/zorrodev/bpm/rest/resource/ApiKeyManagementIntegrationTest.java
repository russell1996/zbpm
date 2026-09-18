package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-8: One API key per user + per-process grants (ADR-2).
 * Full-context IT through real filter chain (V11).
 * Covers criteria #1-#10 from WO-MT-8.
 *
 * Key structure: userA gets ONE key + grants: P1=[START], P4=[COMPLETE_SERVICE_TASK].
 * This allows testing: START P1→200, START P4→403 (missing permission),
 * START P3→403 (no grant at all).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyManagementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessMemberRepository processMemberRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;
    private UUID superAdminId;
    private String userAToken;
    private UUID userAId;
    private String userBToken;
    private UUID userBId;
    private String userAKey;
    private String userBKey;

    private String process1Key;
    private String process2Key;
    private String process3Key;
    private String process4Key;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
        var adminEntity = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole())).findFirst().orElseThrow();
        superAdminId = adminEntity.getId();

        process1Key = deployProcess("mt8-proc1");
        process2Key = deployProcess("mt8-proc2");
        process3Key = deployProcess("mt8-proc3");
        process4Key = deployProcess("mt8-proc4");

        userAId = createUser("mt8-userA", "USER");
        userBId = createUser("mt8-userB", "USER");

        addMember(userAId, process1Key, "OWNER");
        addMember(userAId, process2Key, "DESIGNER");
        addMember(userAId, process3Key, "OWNER");
        addMember(userAId, process4Key, "OWNER");

        // userB also needs membership for grant tests
        addMember(userBId, process1Key, "OWNER");

        userAToken = loginAndGetToken("mt8-userA", "pass");
        userBToken = loginAndGetToken("mt8-userB", "pass");

        // Create API keys for both users (one key per user — stable across tests)
        userAKey = createApiKeyForUser(userAId);
        setGrants(userAId, process1Key, "START", process4Key, "COMPLETE_SERVICE_TASK");

        userBKey = createApiKeyForUser(userBId);
    }

    // ==================== Criterion #1: super-admin creates key + grants → show-once ====================

    @Test
    void criterion1_superAdminCreatesKeyWithGrants_showsOnce() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();

        String getBody = getResult.getResponse().getContentAsString();
        assertFalse(getBody.contains(userAKey), "GET must NOT contain plaintext key");
        assertTrue(getBody.contains(process1Key), "Grant for process1 must be present");
        assertTrue(getBody.contains(process4Key), "Grant for process4 must be present");
    }

    // ==================== Criterion #2: grant-gated auth ====================

    @Test
    void criterion2_grantGatedAuth() throws Exception {
        // Grants: P1=[START], P4=[COMPLETE_SERVICE_TASK]

        // START on P1 → 200 (permission START is granted)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + userAKey)
                        .content("{\"processDefinitionKey\":\"" + process1Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // START on P4 → 403 (P4 only has COMPLETE_SERVICE_TASK, not START)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + userAKey)
                        .content("{\"processDefinitionKey\":\"" + process4Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // GET /process-instances is open (no grant check for reads) → 200
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + userAKey))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #3: grant on process without user access → 400 ====================

    @Test
    void criterion3_grantOnUnaccessibleProcess_returns400() throws Exception {
        // userB has no membership on process3 → grant should fail
        mockMvc.perform(put("/admin/users/" + userBId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + process3Key + "\",\"permissions\":\"START\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ==================== Criterion #4: one key per user ====================

    @Test
    void criterion4_oneKeyPerUser_returns409() throws Exception {
        // userA already has a key (created in @BeforeAll) → second create = 409
        mockMvc.perform(post("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict());
    }

    // ==================== Criterion #5: USER GET /me/api-key ====================

    @Test
    void criterion5_userSeesOwnKeyAndGrants() throws Exception {
        MvcResult result = mockMvc.perform(get("/me/api-key")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertNotNull(body.get("id"));
        assertFalse(body.has("key") && !body.get("key").isNull(), "Secret must NOT be in /me/api-key");
        assertTrue(body.get("grants").size() >= 1, "Must have at least 1 grant");
    }

    // ==================== Criterion #6: USER rotate → new secret, old → 401 ====================

    @Test
    void criterion6_userRotate_keyChange_oldKeyDead() throws Exception {
        // Use userB for rotate test
        setGrants(userBId, process1Key, "START");

        // Rotate via userB's own token
        MvcResult rotateResult = mockMvc.perform(post("/me/api-key/rotate")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(rotateResult.getResponse().getContentAsString());
        String newKey = body.get("key").asText();
        assertNotEquals(userBKey, newKey, "New key must differ from old");
        assertTrue(newKey.startsWith("zbpm_sk_"));

        // Old key → 401
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + userBKey))
                .andExpect(status().isUnauthorized());

        // New key → 200
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + newKey))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #7: USER revoke → 401 ====================

    @Test
    void criterion7_userRevoke_keyDead() throws Exception {
        // Use userB's key for revoke test
        mockMvc.perform(post("/me/api-key/revoke")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk());

        // Revoked key → 401
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + userBKey))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #8: USER create/change grants → 403 ====================

    @Test
    void criterion8_userCannotCreateOrChangeGrants() throws Exception {
        // User cannot access admin endpoint to create
        mockMvc.perform(post("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isForbidden());

        // User cannot change grants via admin endpoint
        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + userAToken)
                        .content("{\"grants\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ==================== Criterion #9: hash-at-rest ====================

    @Test
    void criterion9_hashAtRest() throws Exception {
        var allKeys = apiKeyRepository.findAll();
        assertFalse(allKeys.isEmpty(), "Must have at least one key");
        for (ApiKeyEntity key : allKeys) {
            assertNotNull(key.getKeyHash());
            assertFalse(key.getKeyHash().startsWith("zbpm_sk_"), "key_hash must be SHA-256, not plaintext");
            assertFalse(key.getKeyHash().contains(userAKey), "key_hash must not contain plaintext");
        }
    }

    // ==================== Criterion #10: proof-of-failure — runtime deny on P3 (no grant) ====================

    /**
     * Proof-of-failure (V3): grant-gate on RUNTIME action.
     *
     * RED (before fix): canOperate for SA always returned true for runtime actions.
     *   → START P3 (no grant) = 200 (bypass).
     * GREEN (after fix): canOperate checks grants map.
     *   → START P3 (no grant) = 403.
     *
     * NOTE: test uses POST /process-instances (runtime START action), NOT a management endpoint.
     * This proves the grant-gate actually works for runtime operations.
     */
    @Test
    void criterion10_proofOfFailure_runtimeDenyWithoutGrant() throws Exception {
        // userAKey has grants for P1 and P4, but NOT P3.
        // START on P3 (no grant) → 403 (not management — this is a RUNTIME action).
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + userAKey)
                        .content("{\"processDefinitionKey\":\"" + process3Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ==================== WO-MT-9d review #2: revoke→create ====================

    /**
     * After revoking a key, creating a new one returns 200 (not 409).
     * The old key is dead, the new key works.
     */
    @Test
    void createApiKey_afterRevoke_returnsNewKey() throws Exception {
        UUID testUserId = createUser("mt9d-revoke-test", "USER");
        addMember(testUserId, process1Key, "OWNER");

        // Create key
        MvcResult createResult = mockMvc.perform(post("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isCreated())
            .andReturn();
        String firstKey = mapper.readTree(createResult.getResponse().getContentAsString()).get("key").asText();

        // Revoke
        mockMvc.perform(post("/admin/users/" + testUserId + "/api-key/revoke")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Verify revoked
        mockMvc.perform(get("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        // Create again → 200 (not 409!)
        MvcResult recreateResult = mockMvc.perform(post("/admin/users/" + testUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isCreated())
            .andReturn();
        String secondKey = mapper.readTree(recreateResult.getResponse().getContentAsString()).get("key").asText();

        // Old key dead
        mockMvc.perform(get("/process-instances")
                .header("Authorization", "Bearer " + firstKey))
            .andExpect(status().isUnauthorized());

        // New key works
        mockMvc.perform(get("/process-instances")
                .header("Authorization", "Bearer " + secondKey))
            .andExpect(status().isOk());
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
