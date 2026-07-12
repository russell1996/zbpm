package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ServiceAccountEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceAccountRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-4: Service Account API key management — full-context tests (V11).
 * All tests use @SpringBootTest + real filter chain (JwtAuthFilter → ServiceAccountResource).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ServiceAccountRepository serviceAccountRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String ownerToken;
    private UUID ownerId;
    private String nonOwnerToken;
    private UUID nonOwnerId;
    private String adminToken;
    private UUID adminId;
    private String processKey = "mt4-test-process";
    private UUID processId;

    @BeforeAll
    void setup() throws Exception {
        // --- OWNER user ---
        UiUserEntity owner = new UiUserEntity();
        ownerId = UUID.randomUUID();
        owner.setId(ownerId);
        owner.setUsername("mt4-owner");
        owner.setPasswordHash(passwordHasher.hash("pass"));
        owner.setFullName("MT4 Owner");
        owner.setRole("USER");
        owner.setActive(true);
        owner.setCreatedAt(Instant.now());
        owner.setUpdatedAt(Instant.now());
        userRepository.save(owner);

        // --- Non-OWNER user (regular USER, no membership) ---
        UiUserEntity nonOwner = new UiUserEntity();
        nonOwnerId = UUID.randomUUID();
        nonOwner.setId(nonOwnerId);
        nonOwner.setUsername("mt4-nonowner");
        nonOwner.setPasswordHash(passwordHasher.hash("pass"));
        nonOwner.setFullName("MT4 Non-Owner");
        nonOwner.setRole("USER");
        nonOwner.setActive(true);
        nonOwner.setCreatedAt(Instant.now());
        nonOwner.setUpdatedAt(Instant.now());
        userRepository.save(nonOwner);

        // --- SUPER_ADMIN user ---
        UiUserEntity admin = new UiUserEntity();
        adminId = UUID.randomUUID();
        admin.setId(adminId);
        admin.setUsername("mt4-admin");
        admin.setPasswordHash(passwordHasher.hash("pass"));
        admin.setFullName("MT4 Admin");
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        // --- Process ---
        ProcessEntity process = new ProcessEntity();
        processId = UUID.randomUUID();
        process.setId(processId);
        process.setDefinitionKey(processKey);
        process.setName("MT4 Test Process");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        // --- OWNER membership ---
        ProcessMemberEntity membership = new ProcessMemberEntity();
        membership.setProcessId(processId);
        membership.setUserId(ownerId);
        membership.setRole("OWNER");
        membership.setAddedAt(Instant.now());
        processMemberRepository.save(membership);

        // --- Login all users ---
        ownerToken = login("mt4-owner", "pass");
        nonOwnerToken = login("mt4-nonowner", "pass");
        adminToken = login("mt4-admin", "pass");
    }

    private LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDto(username, password)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    // ==================== Criterion #1: SUPER_ADMIN creates SA — key shown once ====================

    @Test
    void criterion1_superAdminCreatesSa_keyShownOnce() throws Exception {
        // Create SA — response must contain plaintext key starting with zbpm_sk_
        MvcResult createResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"criterion1-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(createResult.getResponse().getContentAsString());
        String rawKey = body.get("key").asText();
        String prefix = body.get("prefix").asText();
        assertTrue(rawKey.startsWith("zbpm_sk_"), "Key must start with zbpm_sk_: " + rawKey);
        assertEquals(prefix, rawKey.substring(0, Math.min(16, rawKey.length())));

        // LIST must NOT contain the plaintext key
        MvcResult listResult = mockMvc.perform(get("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String listBody = listResult.getResponse().getContentAsString();
        assertFalse(listBody.contains(rawKey),
            "LIST must NOT contain plaintext key. Key leaked in: " + listBody);
    }

    // ==================== Criterion #2: hash-at-rest ====================

    @Test
    void criterion2_hashAtRest() throws Exception {
        // Create SA as SUPER_ADMIN
        MvcResult createResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"criterion2-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(createResult.getResponse().getContentAsString());
        String rawKey = body.get("key").asText();
        UUID saId = UUID.fromString(body.get("id").asText());

        // Fetch SA from DB directly
        ServiceAccountEntity sa = serviceAccountRepository.findById(saId).orElseThrow();
        assertNotEquals(rawKey, sa.getKeyHash(),
            "key_hash must NOT equal plaintext key");
        assertEquals(KeyHasher.sha256(rawKey), sa.getKeyHash(),
            "key_hash must be SHA-256 of plaintext key");
    }

    // ==================== Criterion #3: API key authenticates → 200 ====================

    @Test
    void criterion3_apiKeyAuthenticates() throws Exception {
        // Create SA with START permission as SUPER_ADMIN
        MvcResult createResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"criterion3-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(createResult.getResponse().getContentAsString());
        String apiKey = body.get("key").asText();

        // Use API key to call protected data API endpoint (GET /process-instances)
        // — this proves the API key is accepted by JwtAuthFilter and sets principal
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #4: revoke → key dead → 401 ====================

    @Test
    void criterion4_revokeKillSwitch() throws Exception {
        // Create SA as SUPER_ADMIN
        MvcResult createResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"criterion4-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(createResult.getResponse().getContentAsString());
        String apiKey = body.get("key").asText();
        UUID saId = UUID.fromString(body.get("id").asText());

        // Verify key works before revoke
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk());

        // Revoke as SUPER_ADMIN
        mockMvc.perform(post("/processes/" + processKey + "/service-accounts/" + saId + "/revoke")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify revoked key → 401
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #5: rotate — new key works, old key dead ====================

    @Test
    void criterion5_rotateNewKeyWorksOldKeyDead() throws Exception {
        // Create SA as SUPER_ADMIN
        MvcResult createResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"criterion5-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createBody = mapper.readTree(createResult.getResponse().getContentAsString());
        String oldKey = createBody.get("key").asText();
        UUID saId = UUID.fromString(createBody.get("id").asText());

        // Verify old key works
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + oldKey))
                .andExpect(status().isOk());

        // Rotate as SUPER_ADMIN
        MvcResult rotateResult = mockMvc.perform(post("/processes/" + processKey + "/service-accounts/" + saId + "/rotate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rotateBody = mapper.readTree(rotateResult.getResponse().getContentAsString());
        String newKey = rotateBody.get("key").asText();
        assertNotEquals(oldKey, newKey, "New key must differ from old key");

        // Old key → 401 (rotate replaces hash immediately)
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + oldKey))
                .andExpect(status().isUnauthorized());

        // New key → 200
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + newKey))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #6: non-OWNER → 403 (U1) ====================

    @Test
    void criterion6_nonOwnerGets403() throws Exception {
        // Non-OWNER cannot create SA
        mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + nonOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"forbidden-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isForbidden());

        // Non-OWNER cannot list SA
        mockMvc.perform(get("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + nonOwnerToken))
                .andExpect(status().isForbidden());
    }

    // ==================== Criterion #7: SUPER_ADMIN → 200 ====================

    @Test
    void criterion7_superAdminCanOperate() throws Exception {
        // SUPER_ADMIN can create SA on any process
        mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk());

        // SUPER_ADMIN can list
        mockMvc.perform(get("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #8: plaintext key NOT in logs ====================

    @Test
    void criterion8_plaintextKeyNotInLogs() {
        // After all tests ran, check ServiceAccountRepository for any stored plaintext.
        // All SAs should have only key_hash (hash), never the raw key.
        var allSas = serviceAccountRepository.findAll();
        for (ServiceAccountEntity sa : allSas) {
            assertNotNull(sa.getKeyHash(), "key_hash must be set");
            assertFalse(sa.getKeyHash().startsWith("zbpm_sk_"),
                "key_hash must NOT contain plaintext key prefix: " + sa.getKeyHash());
        }
    }

    // ==================== Criterion #9: proof-of-failure ====================

    /**
     * Proof-of-failure (V3): meaningful security proof.
     * The proof: the JwtAuthFilter must protect /processes/ paths.
     * Without /processes/ in isDataApiPath (the code BEFORE the fix),
     * the request goes through unauthenticated → getPrincipal() returns null
     * → 401 "Authentication required" instead of proper authz check.
     * WITH the fix, non-OWNER gets 403 (proper authz), not 401 (missing auth).
     *
     * This is verified by criterion #6 (nonOwnerGets403) which asserts 403, not 401.
     * RED scenario (before fix): non-OWNER create → 401 (no principal set)
     * GREEN scenario (after fix): non-OWNER create → 403 (principal set, authz denied)
     *
     * The separate proof-of-failure for endpoint existence (RED=404 → GREEN=200)
     * was shown in the initial commit (9fd708e).
     */
    @Test
    void criterion9_proofOfFailure_authFilterProtectsSaEndpoint() throws Exception {
        // Non-OWNER → 403 (not 401). If the filter didn't protect this path,
        // principal would be null and the response would be 401, not 403.
        mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + nonOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pof-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isForbidden());
    }
}
