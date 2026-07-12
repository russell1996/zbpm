package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-10: Audit-log integration tests (V11, full-context).
 * Tests that mutations produce audit log entries with correct attribution.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogIntegrationTest {

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
    private String userAToken;
    private UUID userAId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
        superAdminId = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole())).findFirst().orElseThrow().getId();

        userAId = createUser("mt10-userA");
        processKey = deployProcess("mt10-proc");

        // Add userA as OWNER
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userAId + "\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk());

        userAToken = loginAndGetToken("mt10-userA", "pass");
    }

    // ==================== Criterion #1: User mutation → audit entry ====================

    @Test
    void criterion1_userMutation_createsAuditEntry() throws Exception {
        long beforeCount = auditLogRepository.count();

        // Start a process instance as userA
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processDefinitionKey\":\"" + processKey + "\",\"variables\":[]}"))
            .andExpect(status().isOk());

        // Verify audit entry was created
        long afterCount = auditLogRepository.count();
        assertEquals(beforeCount + 1, afterCount, "Audit entry should be created");

        var entries = auditLogRepository.findByFilters(processKey, null, null, null);
        assertFalse(entries.isEmpty(), "Audit entries should exist for this process");
        var entry = entries.get(0);
        assertEquals("USER", entry.getPrincipalType());
        assertEquals(userAId.toString(), entry.getPrincipalId());
        assertEquals("START", entry.getAction());
        assertNotNull(entry.getAt());
    }

    // ==================== Criterion #2: API key mutation → correct attribution ====================

    @Test
    void criterion2_apiKeyMutation_correctAttribution() throws Exception {
        // Create API key for userA
        MvcResult createResult = mockMvc.perform(post("/admin/users/" + userAId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();

        // Verify audit entry for KEY_CREATE with correct target
        var entries = auditLogRepository.findByFilters(null, null, null, null);
        var keyCreate = entries.stream()
            .filter(e -> "KEY_CREATE".equals(e.getAction()))
            .findFirst();
        assertTrue(keyCreate.isPresent(), "KEY_CREATE audit entry should exist");
        assertNotNull(keyCreate.get().getPrincipalId(), "principal_id must be set");
        assertEquals(userAId.toString(), keyCreate.get().getTargetId(), "target_id must be the user");
    }

    // ==================== Criterion #3: GET /admin/audit-log ====================

    @Test
    void criterion3_auditLogEndpoint_worksForSuperAdmin() throws Exception {
        mockMvc.perform(get("/admin/audit-log")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // Filter by processKey
        mockMvc.perform(get("/admin/audit-log")
                .queryParam("processKey", processKey)
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());
    }

    @Test
    void criterion3_nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(get("/admin/audit-log")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isForbidden());
    }

    // ==================== Criterion #4: Reads don't create audit entries ====================

    @Test
    void criterion4_readsDoNotCreateAuditEntries() throws Exception {
        long beforeCount = auditLogRepository.count();

        // Read process definitions
        mockMvc.perform(get("/process-definitions")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk());

        // Read process instances
        mockMvc.perform(get("/process-instances")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk());

        long afterCount = auditLogRepository.count();
        assertEquals(beforeCount, afterCount, "Reads should not create audit entries");
    }

    // ==================== Criterion #5: No secrets in audit ====================

    @Test
    void criterion5_noSecretsInAudit() throws Exception {
        // Use a fresh user to avoid 409 (key already exists from criterion2)
        UUID freshUserId = createUser("mt10-fresh");
        MvcResult createResult = mockMvc.perform(post("/admin/users/" + freshUserId + "/api-key")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = mapper.readTree(createResult.getResponse().getContentAsString());
        String rawKey = body.get("key").asText();

        // Verify no audit entry contains the raw key
        var allEntries = auditLogRepository.findAll();
        for (var entry : allEntries) {
            assertFalse(entry.getAction().contains(rawKey), "Audit action must not contain raw key");
            if (entry.getProcessKey() != null) {
                assertFalse(entry.getProcessKey().contains(rawKey), "Audit process_key must not contain raw key");
            }
        }

        // Verify no variable values in target_id
        for (var entry : allEntries) {
            if (entry.getTargetId() != null) {
                assertFalse(entry.getTargetId().contains("value="), "target_id must not contain variable values");
            }
        }
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

    private UUID createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
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
}
