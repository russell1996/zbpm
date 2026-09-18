package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-ACL-5: self-service API keys (ADR-8 п.5).
 * Full-context IT through the real filter chain (V11):
 * <ul>
 *   <li>criterion #1 — user issues own key, secret shown once, second issue → 409;</li>
 *   <li>criterion #2 — grant on a process the user is not a member of → 400;</li>
 *   <li>criterion #3 (POF) — membership revocation (role demotion AND removal) narrows an
 *       already-issued key without touching the key: 200 before, 403 after;</li>
 *   <li>criterion #4 — deactivated owner → key stops working (401);</li>
 *   <li>criterion #5 — SUPER_ADMIN still manages foreign keys (regression);</li>
 *   <li>criterion #6 — a full grant never exceeds the owner's current role rights.</li>
 * </ul>
 * Each test uses its own user + process (no cross-test state).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl5SelfServiceTokensIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessMemberRepository processMemberRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
    }

    // ==================== Criterion #1: user issues own key, secret shown once ====================

    @Test
    void criterion1_userIssuesOwnKey_secretShownOnce_secondIssue409() throws Exception {
        UUID userId = createUser("acl5-c1", "USER");
        String userToken = loginAndGetToken("acl5-c1", "pass");

        // Issue own key → 200, secret present (show-once)
        MvcResult createResult = mockMvc.perform(post("/me/api-key")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createBody = mapper.readTree(createResult.getResponse().getContentAsString());
        String issuedKey = createBody.get("key").asText();
        assertTrue(issuedKey.startsWith("zbpm_sk_"), "issued key must be a service key");
        assertNotNull(createBody.get("id"));

        // GET /me/api-key must NOT contain the secret
        MvcResult getResult = mockMvc.perform(get("/me/api-key")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        String getBody = getResult.getResponse().getContentAsString();
        assertFalse(getBody.contains(issuedKey), "secret must be shown only once");

        // Second issue while active → 409 (one key per user, ADR-2)
        mockMvc.perform(post("/me/api-key")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isConflict());
    }

    // ==================== Criterion #2: grant on inaccessible process → 400 ====================

    @Test
    void criterion2_selfGrantOnInaccessibleProcess_rejected400() throws Exception {
        UUID userId = createUser("acl5-c2", "USER");
        String userToken = loginAndGetToken("acl5-c2", "pass");
        String accessibleKey = deployProcess("acl5_c2a");
        String inaccessibleKey = deployProcess("acl5_c2b");
        addMember(userId, accessibleKey, "OWNER");
        // userId is NOT a member of acl5_c2b

        String ownKey = createMyApiKey(userToken);

        // Grant on a process the user is not a member of → 400, nothing changed
        mockMvc.perform(put("/me/api-key/grants")
                        .header("Authorization", "Bearer " + userToken)
                        .content("{\"grants\":[{\"processKey\":\"" + inaccessibleKey + "\",\"permissions\":\"START\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Grant on a process the user IS a member of → 200
        mockMvc.perform(put("/me/api-key/grants")
                        .header("Authorization", "Bearer " + userToken)
                        .content("{\"grants\":[{\"processKey\":\"" + accessibleKey + "\",\"permissions\":\"START\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Only the allowed grant is present
        MvcResult getResult = mockMvc.perform(get("/me/api-key")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode grants = mapper.readTree(getResult.getResponse().getContentAsString()).get("grants");
        assertTrue(grants.size() >= 1, "grant for accessible process must be present");
        boolean hasAccessible = false;
        for (JsonNode g : grants) {
            String processKey = g.get("processKey").asText();
            if (accessibleKey.equals(processKey)) hasAccessible = true;
            assertNotEquals(inaccessibleKey, processKey, "grant on inaccessible process must not exist");
        }
        assertTrue(hasAccessible, "grant on accessible process must exist");
    }

    // ==================== Criterion #3 (POF): membership revocation narrows an issued key ====================

    /**
     * Proof-of-failure (V3, G-K): effective rights = intersection(owner's CURRENT rights, key grants),
     * computed at request time (ADR-8 п.5).
     * RED (before fix): grants are frozen at issue time → after role demotion (grants stay in the DB)
     * the key still has START → 200.
     * GREEN (after fix): effectiveGrants drops START (VIEWER has no START) → 403.
     */
    @Test
    void criterion3_membershipRevocation_narrowsExistingKey() throws Exception {
        UUID ownerId = createUser("acl5-c3", "USER");
        UUID otherOwnerId = createUser("acl5-c3-other", "USER");
        String userToken = loginAndGetToken("acl5-c3", "pass");
        String processKey = deployProcess("acl5_c3");
        addMember(ownerId, processKey, "OWNER");
        addMember(otherOwnerId, processKey, "OWNER"); // second OWNER so the demotion is allowed

        String key = createMyApiKey(userToken);
        setMyGrants(userToken, processKey, "START");

        // Before revocation: START works
        startProcess(processKey, key).andExpect(status().isCreated());

        // Revoke access: demote OWNER → VIEWER through the real member endpoint.
        // changeRole does NOT delete key grants — the grant stays in the DB, only the
        // intersection (effectiveGrants) can narrow the key. This is the WO-ACL-5 hole.
        mockMvc.perform(patch("/processes/" + processKey + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"role\":\"VIEWER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // After revocation: same key, same grant in DB → 403 (narrowed by intersection)
        startProcess(processKey, key).andExpect(status().isForbidden());

        // Removal path too: member removed entirely → still 403 (grant cascade + intersection)
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        startProcess(processKey, key).andExpect(status().isForbidden());
    }

    // ==================== Criterion #4: deactivated owner → key stops working ====================

    @Test
    void criterion4_deactivatedOwner_keyFails401() throws Exception {
        UUID userId = createUser("acl5-c4", "USER");
        String userToken = loginAndGetToken("acl5-c4", "pass");
        String processKey = deployProcess("acl5_c4");
        addMember(userId, processKey, "OWNER");

        String key = createMyApiKey(userToken);
        setMyGrants(userToken, processKey, "START");

        // Active owner: key works
        startProcess(processKey, key).andExpect(status().isCreated());

        // Deactivate the owner through the real user-management endpoint
        mockMvc.perform(put("/users/" + userId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"active\":false}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Deactivated owner → key no longer authenticates (401)
        startProcess(processKey, key).andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #5 (regression): SUPER_ADMIN still manages foreign keys ====================

    @Test
    void criterion5_superAdminStillManagesForeignKeys() throws Exception {
        UUID userId = createUser("acl5-c5", "USER");
        String processKey = deployProcess("acl5_c5");
        addMember(userId, processKey, "OWNER");

        // Create on behalf of the user
        MvcResult createResult = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isCreated())
                .andReturn();
        String firstKey = mapper.readTree(createResult.getResponse().getContentAsString()).get("key").asText();

        // Set grants on behalf of the user
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"permissions\":\"START\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Rotate on behalf of the user → old key dead, new key works
        MvcResult rotateResult = mockMvc.perform(post("/admin/users/" + userId + "/api-key/rotate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        String rotatedKey = mapper.readTree(rotateResult.getResponse().getContentAsString()).get("key").asText();
        startProcess(processKey, rotatedKey).andExpect(status().isCreated());
        startProcess(processKey, firstKey).andExpect(status().isUnauthorized());

        // Revoke on behalf of the user → dead
        mockMvc.perform(post("/admin/users/" + userId + "/api-key/revoke")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        startProcess(processKey, rotatedKey).andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #6: key never exceeds owner's rights, even with full grant ====================

    @Test
    void criterion6_fullGrant_cannotExceedOwnerRights() throws Exception {
        UUID userId = createUser("acl5-c6", "USER");
        String userToken = loginAndGetToken("acl5-c6", "pass");
        String processKey = deployProcess("acl5_c6");
        addMember(userId, processKey, "VIEWER"); // read-only role

        String key = createMyApiKey(userToken);

        // The user IS a member (VIEWER), so a full grant passes self-service validation...
        mockMvc.perform(put("/me/api-key/grants")
                        .header("Authorization", "Bearer " + userToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // ...but the intersection narrows it to the VIEWER action set:
        // START is not a VIEWER right → 403 despite the full grant
        startProcess(processKey, key).andExpect(status().isForbidden());

        // The narrowed key still works for what VIEWER can do: read members
        mockMvc.perform(get("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + key))
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
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"bpmn\":" + mapper.writeValueAsString(bpmn) + "}")
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

    private String createMyApiKey(String userToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/me/api-key")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setMyGrants(String userToken, String processKey, String permissions) throws Exception {
        mockMvc.perform(put("/me/api-key/grants")
                        .header("Authorization", "Bearer " + userToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"permissions\":\"" + permissions + "\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions startProcess(String processKey, String key) throws Exception {
        return mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + key)
                        .content("{\"processDefinitionKey\":\"" + processKey + "\",\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON));
    }
}
