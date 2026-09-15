package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-4 (ADR-8 п.3): owner updates the model from inside the process —
 * {@code POST /process-definitions/{id}/versions}.
 * Order is the point: authorize by {@code {id}} (DEPLOY) → parse → verify key matches target → save.
 * Full-context tests (V11) through the real filter chain.
 *
 * POF (G-K): criterion2 (foreign key rejected, nothing persisted) is RED if the key check is
 * removed — the foreign model would be deployed as a new version under its own key.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl4OwnerUpdatesModelIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdKeys = new ArrayList<>();
    private String adminToken;
    private UUID ownerId;
    private String ownerToken;
    private String designerToken;
    private String viewerToken;
    private String outsiderToken;
    private String bpmn;

    @BeforeAll
    void setup() throws Exception {
        bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));

        UUID adminId = createUser("acl4-admin", "SUPER_ADMIN");
        ownerId = createUser("acl4-owner", "USER");
        createUser("acl4-designer", "USER");
        createUser("acl4-viewer", "USER");
        createUser("acl4-outsider", "USER");

        adminToken = login("acl4-admin", "pass");
        ownerToken = login("acl4-owner", "pass");
        designerToken = login("acl4-designer", "pass");
        viewerToken = login("acl4-viewer", "pass");
        outsiderToken = login("acl4-outsider", "pass");
    }

    private UUID createUser(String username, String globalRole) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("ACL4 " + username);
        user.setRole(globalRole);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return user.getId();
    }

    private String login(String username, String password) throws Exception {
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

    private String uniqueKey() {
        return "acl4_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Deploys the base bpmn under {@code key} as SUPER_ADMIN, returns the created definition id. */
    private UUID deployAsAdmin(String key) throws Exception {
        String testBpmn = bpmnFor(key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(testBpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        createdKeys.add(key);
        return mapper.readValue(result.getResponse().getContentAsString(), ProcessDefinition.class).getId();
    }

    private String bpmnFor(String key) {
        return bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                .replace("process id=\"process1\"", "process id=\"" + key + "\"");
    }

    private void addMemberAs(String token, String key, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}"))
                .andReturn();
    }

    private MvcResult postVersion(String token, UUID id, String bpmnXml) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnXml);
        return mockMvc.perform(post("/process-definitions/" + id + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private long versionsOf(String key) {
        return processDefinitionRepository.findAll((root, q, cb) -> cb.equal(root.get("key"), key)).size();
    }

    private boolean registryHas(String key) {
        return processRepository.findByDefinitionKey(key).isPresent();
    }

    @AfterEach
    void cleanupDeployedDefinitions() {
        for (String key : createdKeys) {
            processRepository.findByDefinitionKey(key).ifPresent(p -> {
                processMemberRepository.findByProcessId(p.getId()).forEach(processMemberRepository::delete);
                processRepository.delete(p);
            });
            List<ProcessDefinitionEntity> defs = processDefinitionRepository.findAll(
                (root, q, cb) -> cb.equal(root.get("key"), key));
            processDefinitionRepository.deleteAll(defs);
        }
        createdKeys.clear();
    }

    // ==================== Criterion 1: OWNER deploys a new version, version increments ====================

    @Test
    void criterion1_ownerUploadsNewVersion_versionIncrements() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(ownerToken, pdId, bpmnFor(key).replace("name=\"" + key + "\"", "name=\"" + key + "-v2\""));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        ProcessDefinition created = mapper.readValue(result.getResponse().getContentAsString(), ProcessDefinition.class);
        assertEquals(key, created.getKey());
        assertEquals(2, created.getVersion(), "second version of the same key must be v2");
        assertEquals(2, versionsOf(key), "both versions must be persisted");
    }

    // ==================== Criterion 2: foreign key in own process → 400, nothing saved (POF) ====================

    @Test
    void criterion2_foreignKeyInOwnProcess_rejectedNothingSaved() throws Exception {
        String key = uniqueKey();
        String foreignKey = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(ownerToken, pdId, bpmnFor(foreignKey));
        assertEquals(400, result.getResponse().getStatus(),
            "foreign key must be rejected: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("key"),
            "rejection must explain the key mismatch: " + result.getResponse().getContentAsString());

        assertEquals(1, versionsOf(key), "target process must keep its single version");
        assertEquals(0, versionsOf(foreignKey), "no definition with the foreign key may exist");
        assertTrue(!registryHas(foreignKey), "no registry process with the foreign key may exist");
    }

    // ==================== Criterion 3: non-member cannot update a foreign process ====================

    @Test
    void criterion3_nonMemberCannotUpdateForeignProcess() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(outsiderToken, pdId, bpmnFor(key));
        assertEquals(403, result.getResponse().getStatus(),
            "non-member must be denied: " + result.getResponse().getContentAsString());
        assertEquals(1, versionsOf(key));
    }

    @Test
    void criterion3b_unknownDefinitionId_is404() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(ownerToken, UUID.randomUUID(), bpmnFor(key));
        assertEquals(404, result.getResponse().getStatus(),
            "unknown id must be 404: " + result.getResponse().getContentAsString());
    }

    // ==================== Criterion 4: VIEWER of own process cannot update ====================

    @Test
    void criterion4_viewerCannotUpdateOwnProcess() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");
        UUID viewerId = createUser("acl4-viewer-" + key.substring(5), "USER");
        addMemberAs(adminToken, key, viewerId, "VIEWER");
        String viewerToken = login("acl4-viewer-" + key.substring(5), "pass");

        MvcResult result = postVersion(viewerToken, pdId, bpmnFor(key));
        assertEquals(403, result.getResponse().getStatus(),
            "VIEWER must be denied: " + result.getResponse().getContentAsString());
        assertEquals(1, versionsOf(key));
    }

    // ==================== Criterion 5: SUPER_ADMIN can update any process ====================

    @Test
    void criterion5_superAdminCanUpdateAnyProcess() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(adminToken, pdId, bpmnFor(key).replace("name=\"" + key + "\"", "name=\"" + key + "-admin\""));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        ProcessDefinition created = mapper.readValue(result.getResponse().getContentAsString(), ProcessDefinition.class);
        assertEquals(2, created.getVersion(), "admin's version must also increment");
        assertEquals(2, versionsOf(key));
    }

    // ==================== Criterion 6: unparsable BPMN → 400, nothing saved ====================

    @Test
    void criterion6_unparsableBpmnRejected_nothingSaved() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);
        addMemberAs(adminToken, key, ownerId, "OWNER");

        MvcResult result = postVersion(ownerToken, pdId, "<not-a-bpmn>");
        assertEquals(400, result.getResponse().getStatus(),
            "unparsable BPMN must be rejected: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("parsed"),
            "rejection must be actionable: " + result.getResponse().getContentAsString());
        assertEquals(1, versionsOf(key), "nothing may be persisted");
    }

    // ==================== Endpoint requires authentication ====================

    @Test
    void versionEndpointRequiresAuthentication() throws Exception {
        String key = uniqueKey();
        UUID pdId = deployAsAdmin(key);

        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnFor(key));
        mockMvc.perform(post("/process-definitions/" + pdId + "/versions")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}