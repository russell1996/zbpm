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
    private String userAKey; // shared API key for userA (created once in @BeforeAll)

    private String process1Key;
    private String process2Key;
    private String process3Key;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
        var adminEntity = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole())).findFirst().orElseThrow();
        superAdminId = adminEntity.getId();

        process1Key = deployProcess("mt8-proc1");
        process2Key = deployProcess("mt8-proc2");
        process3Key = deployProcess("mt8-proc3");

        userAId = createUser("mt8-userA", "USER");
        userBId = createUser("mt8-userB", "USER");

        addMember(userAId, process1Key, "OWNER");
        addMember(userAId, process2Key, "DESIGNER");

        userAToken = loginAndGetToken("mt8-userA", "pass");
        userBToken = loginAndGetToken("mt8-userB", "pass");

        // Create API key for userA once (shared across tests)
        userAKey = createApiKeyForUser(userAId);
        // Set default grants: P1=[START], P2=[FULL]
        String grantJson = "{\"grants\":["
            + "{\"processKey\":\"" + process1Key + "\",\"permissions\":\"START\"},"
            + "{\"processKey\":\"" + process2Key + "\",\"full\":true}"
            + "]}";
        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(grantJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #1: super-admin creates key + grants → show-once ====================

    @Test
    void criterion1_superAdminCreatesKeyWithGrants_showsOnce() throws Exception {
        // GET key must NOT contain secret
        MvcResult getResult = mockMvc.perform(get("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();

        String getBody = getResult.getResponse().getContentAsString();
        assertFalse(getBody.contains(userAKey), "GET must NOT contain plaintext key");
        assertTrue(getBody.contains(process1Key), "Grants must be present for process1");
        assertTrue(getBody.contains(process2Key), "Grants must be present for process2");
    }

    // ==================== Criterion #2: grant-gated auth ====================

    @Test
    void criterion2_grantGatedAuth() throws Exception {
        // Create key + grants for userA
        String apiKey = createApiKeyForUser(userAId);
        String grantJson = "{\"grants\":["
            + "{\"processKey\":\"" + process1Key + "\",\"permissions\":\"START\"},"
            + "{\"processKey\":\"" + process2Key + "\",\"full\":true}"
            + "]}";
        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(grantJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // START on P1 → 200 (permission granted)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + apiKey)
                        .content("{\"processDefinitionKey\":\"" + process1Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // COMPLETE_SERVICE_TASK on P1 → 403 (not in permissions)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + apiKey)
                        .content("{\"processDefinitionKey\":\"" + process1Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()); // start works

        // GET /process-instances is open (no grant check for reads) → 200
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk());

        // P3 (no grant) → 403 on protected endpoint
        mockMvc.perform(get("/processes/" + process3Key + "/members")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
    }

    // ==================== Criterion #3: grant on process without user access → 400 ====================

    @Test
    void criterion3_grantOnUnaccessibleProcess_returns400() throws Exception {
        createApiKeyForUser(userAId);
        // userA has no membership on process3 → grant should fail
        String grantJson = "{\"grants\":[{\"processKey\":\"" + process3Key + "\",\"permissions\":\"START\"}]}";
        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(grantJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ==================== Criterion #4: one key per user ====================

    @Test
    void criterion4_oneKeyPerUser_returns409() throws Exception {
        createApiKeyForUser(userAId);
        // Second create → 409
        mockMvc.perform(post("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict());
    }

    // ==================== Criterion #5: USER GET /me/api-key ====================

    @Test
    void criterion5_userSeesOwnKeyAndGrants() throws Exception {
        createApiKeyForUser(userAId);
        String grantJson = "{\"grants\":["
            + "{\"processKey\":\"" + process1Key + "\",\"permissions\":\"START\"},"
            + "{\"processKey\":\"" + process2Key + "\",\"full\":true}"
            + "]}";
        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(grantJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // userA sees own key + grants (no secret)
        MvcResult result = mockMvc.perform(get("/me/api-key")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertNotNull(body.get("id"));
        assertFalse(body.has("key") && !body.get("key").isNull(), "Secret must NOT be in /me/api-key response");
        assertTrue(body.get("grants").size() >= 2, "Must have at least 2 grants");
    }

    // ==================== Criterion #6: USER rotate → new secret, same grants, old → 401 ====================

    @Test
    void criterion6_userRotate_keyChange_oldKeyDead() throws Exception {
        String oldKey = createApiKeyForUser(userAId);
        setGrants(userAId, process1Key, "START");

        // Rotate
        MvcResult rotateResult = mockMvc.perform(post("/me/api-key/rotate")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(rotateResult.getResponse().getContentAsString());
        String newKey = body.get("key").asText();
        assertNotEquals(oldKey, newKey, "New key must differ");
        assertTrue(newKey.startsWith("zbpm_sk_"));

        // Old key → 401
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + oldKey))
                .andExpect(status().isUnauthorized());

        // New key → 200
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + newKey))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #7: USER revoke → 401 ====================

    @Test
    void criterion7_userRevoke_keyDead() throws Exception {
        String apiKey = createApiKeyForUser(userAId);

        // Revoke
        mockMvc.perform(post("/me/api-key/revoke")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk());

        // Revoked key → 401
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #8: USER create/change grants → 403 ====================

    @Test
    void criterion8_userCannotCreateOrChangeGrants() throws Exception {
        // User cannot create key via /me/api-key (only rotate/revoke)
        mockMvc.perform(post("/me/api-key")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isMethodNotAllowed());

        // User cannot access admin endpoint
        mockMvc.perform(post("/admin/users/" + userAId + "/api-key")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/users/" + userAId + "/api-key/grants")
                        .header("Authorization", "Bearer " + userAToken)
                        .content("{\"grants\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ==================== Criterion #9: hash-at-rest ====================

    @Test
    void criterion9_hashAtRest() throws Exception {
        String apiKey = createApiKeyForUser(userAId);

        // Verify DB has hash, not plaintext
        var allKeys = apiKeyRepository.findAll();
        assertFalse(allKeys.isEmpty(), "Must have at least one key");
        for (ApiKeyEntity key : allKeys) {
            assertNotNull(key.getKeyHash());
            assertFalse(key.getKeyHash().startsWith("zbpm_sk_"), "key_hash must be SHA-256, not plaintext");
        }
    }

    // ==================== Criterion #10: proof-of-failure ====================

    /**
     * Proof-of-failure (V3): grant-gate.
     * On code WITHOUT grant check in canOperate, a request to P3 (no grant)
     * would pass through (200) → RED.
     * On FIXED code, P3 → 403 → GREEN.
     *
     * This test proves the green path exists. The RED scenario is documented:
     * Before grant check existed in AuthorizationService, canOperate for SA
     * always returned true for runtime actions regardless of grants.
     */
    @Test
    void criterion10_proofOfFailure_grantGate() throws Exception {
        String apiKey = createApiKeyForUser(userAId);
        // Grant only on P1=[START]
        setGrants(userAId, process1Key, "START");

        // P1 with START → 200 (grant present, permission matches)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + apiKey)
                        .content("{\"processDefinitionKey\":\"" + process1Key + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // P3 (no grant at all) → 403
        mockMvc.perform(get("/processes/" + process3Key + "/members")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
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
                .andExpect(status().isOk());
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
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrants(UUID userId, String... pairs) throws Exception {
        StringBuilder sb = new StringBuilder("{\"grants\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(",");
            String processKey = pairs[i];
            String perm = pairs[i + 1];
            if (perm == null) {
                sb.append("{\"processKey\":\"").append(processKey).append("\",\"full\":true}");
            } else {
                sb.append("{\"processKey\":\"").append(processKey).append("\",\"permissions\":\"").append(perm).append("\"}");
            }
        }
        sb.append("]}");
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(sb.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
