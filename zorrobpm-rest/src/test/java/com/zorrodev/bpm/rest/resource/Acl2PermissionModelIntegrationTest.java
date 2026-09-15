package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-2 (ADR-8 п.4, п.7): permission model — roles as enum, OWNER manages own process,
 * last-OWNER protection in one place, listMembers ≠ manage, no global-role escalation.
 * Full-context tests (V11) through the real filter chain.
 *
 * POF (G-K):
 *  - criterion4 (single OWNER demoted via changeRole) is RED on current code (200 instead of 409);
 *  - criterion1 (OWNER adds member) is RED on current code (403 instead of 200).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl2PermissionModelIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdKeys = new ArrayList<>();
    private String adminToken;
    private UUID adminId;
    private String ownerToken;
    private UUID ownerId;
    private String designerId; // plain user used as a target of member management
    private UUID viewerId;
    private String bpmn;

    @BeforeAll
    void setup() throws Exception {
        bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));

        adminId = createUser("acl2-admin", "SUPER_ADMIN");
        ownerId = createUser("acl2-owner", "USER");
        UUID designerUuid = createUser("acl2-designer", "USER");
        designerId = designerUuid.toString();
        viewerId = createUser("acl2-viewer", "USER");

        adminToken = login("acl2-admin", "pass");
        ownerToken = login("acl2-owner", "pass");
    }

    private UUID createUser(String username, String globalRole) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("ACL2 " + username);
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
        return "acl2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String deployAsAdmin(String key) throws Exception {
        String testBpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(testBpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        createdKeys.add(key);
        return key;
    }

    /**
     * Tests share one Spring context and one in-memory DB. Deployed definitions accumulate in
     * process_definition and shift the pageSize=50 boundary of ProcessDefinitionResourceIntegrationTests
     * (name-desc binary collation pushes lowercase keys above "Assignee Process"). Clean up what
     * THIS class created so the shared state returns to the pre-class shape.
     */
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

    private void addMemberAs(String token, String key, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}"))
                .andReturn();
    }

    private void changeRoleAs(String token, String key, UUID userId, String role) throws Exception {
        mockMvc.perform(patch("/processes/" + key + "/members/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andReturn();
    }

    private List<ProcessMemberEntity> membersOf(String key) {
        UUID processId = processRepository.findByDefinitionKey(key).orElseThrow().getId();
        return processMemberRepository.findByProcessId(processId);
    }

    // ==================== Criterion 1: OWNER adds and removes a member of HIS process ====================

    /**
     * POF #2 (WO-ACL-2): on pre-fix code canOperate(MANAGE_MEMBERS) returned false for any UserPrincipal
     * (management actions were denied before the role lookup) → OWNER got 403. After the fix → 200.
     */
    @Test
    void criterion1_ownerAddsAndRemovesMember_returns200() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, key, ownerId, "OWNER");
        UUID memberUuid = UUID.fromString(designerId);

        // OWNER adds a member to his own process → 200
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + memberUuid + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());

        // The member is actually persisted with the requested role
        List<ProcessMemberEntity> members = membersOf(key);
        assertTrue(members.stream().anyMatch(m -> m.getUserId().equals(memberUuid) && "DESIGNER".equals(m.getRole())),
                "member must be persisted with role DESIGNER");

        // OWNER removes the member → 200
        mockMvc.perform(delete("/processes/" + key + "/members/" + memberUuid)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertTrue(membersOf(key).stream().noneMatch(m -> m.getUserId().equals(memberUuid)),
                "member must be removed");
    }

    // ==================== Criterion 2: OWNER of A cannot touch members of B ====================

    @Test
    void criterion2_ownerA_cannotManageMembersOfProcessB_returns403() throws Exception {
        String keyA = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, keyA, ownerId, "OWNER");
        String keyB = deployAsAdmin(uniqueKey()); // owner is NOT a member of B
        UUID target = UUID.fromString(designerId);

        mockMvc.perform(post("/processes/" + keyB + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + target + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/processes/" + keyB + "/members/" + target)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        assertTrue(membersOf(keyB).stream().noneMatch(m -> m.getUserId().equals(target)),
                "no member must be created in process B");
    }

    // ==================== Criterion 3: unknown role → 400, member not created ====================

    /**
     * POF (additional): on current code role is a free string — a typo "Owner" is silently persisted
     * (200). After the enum fix → 400 and nothing persisted.
     */
    @Test
    void criterion3_unknownRole_returns400_memberNotCreated() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        UUID target = UUID.fromString(designerId);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + target + "\",\"role\":\"Owner\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("OWNER"),
                        org.hamcrest.Matchers.containsString("Validation"))));

        assertTrue(membersOf(key).stream().noneMatch(m -> m.getUserId().equals(target)),
                "member with unknown role must NOT be created");
    }

    // ==================== Criterion 4: the single OWNER cannot be demoted via changeRole ====================

    /**
     * POF #1 (WO-ACL-2, the central one): on current code changeRole has NO last-OWNER guard —
     * demoting the single OWNER to VIEWER returns 200 and leaves the process ownerless.
     * After the fix → 409 and role unchanged.
     */
    @Test
    void criterion4_singleOwnerDemoteViaChangeRole_returns409_roleUnchanged() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, key, ownerId, "OWNER");
        UUID target = UUID.fromString(designerId);
        addMemberAs(adminToken, key, target, "VIEWER");

        // Try to demote the only OWNER
        mockMvc.perform(patch("/processes/" + key + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());

        // Role must remain OWNER
        ProcessMemberEntity owner = processMemberRepository.findById(
                new ProcessMemberId(processRepository.findByDefinitionKey(key).orElseThrow().getId(), ownerId))
                .orElseThrow();
        assertEquals("OWNER", owner.getRole(), "the single OWNER must not be demoted");
    }

    // ==================== Criterion 5: the single OWNER cannot be removed (regression) ====================

    @Test
    void criterion5_singleOwnerRemove_returns409() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, key, ownerId, "OWNER");
        UUID target = UUID.fromString(designerId);
        addMemberAs(adminToken, key, target, "VIEWER");

        mockMvc.perform(delete("/processes/" + key + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        assertTrue(membersOf(key).stream().anyMatch(m -> m.getUserId().equals(ownerId)),
                "the single OWNER must not be removed");
    }

    // ==================== Criterion 6: members & roles visible to anyone who can see the process ====================

    /**
     * POF (additional): on current code listMembers requires MANAGE_MEMBERS → a VIEWER gets 403.
     * After the fix listMembers is a read action available to every process member (VIEWER included).
     */
    @Test
    void criterion6_viewerCanListMembers_returns200() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, key, ownerId, "OWNER");
        addMemberAs(adminToken, key, viewerId, "VIEWER");

        mockMvc.perform(get("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + login("acl2-viewer", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + ownerId + "')].role").value("OWNER"));
    }

    // ==================== Criterion 7: no global role assignable through member management ====================

    /**
     * POF (additional): on current code "SUPER_ADMIN" is an accepted free string (200) — a member with a
     * global role name is persisted. After the fix the DTO role type contains only process roles
     * (OWNER/DESIGNER/VIEWER) — "SUPER_ADMIN" fails deserialization → 400, structurally.
     */
    @Test
    void criterion7_globalRoleNotAssignableViaMembers_returns400() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        UUID target = UUID.fromString(designerId);

        // addMember with a global role
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + target + "\",\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());

        // changeRole to a global role
        addMemberAs(adminToken, key, target, "VIEWER");
        mockMvc.perform(patch("/processes/" + key + "/members/" + target)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());

        assertTrue(membersOf(key).stream().noneMatch(m -> m.getUserId().equals(target) && !"VIEWER".equals(m.getRole())),
                "no member with a global role name may be persisted");
    }

    // ==================== Criterion 8: SUPER_ADMIN can still do everything (regression) ====================

    @Test
    void criterion8_superAdminStillCanManageMembers_returns200() throws Exception {
        String key = deployAsAdmin(uniqueKey());
        UUID target = UUID.fromString(designerId);

        addMemberAs(adminToken, key, target, "DESIGNER");
        assertTrue(membersOf(key).stream().anyMatch(m -> m.getUserId().equals(target)));

        mockMvc.perform(delete("/processes/" + key + "/members/" + target)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertTrue(membersOf(key).stream().noneMatch(m -> m.getUserId().equals(target)));

        // SUPER_ADMIN may demote an OWNER when another OWNER remains (invariant preserved)
        String key2 = deployAsAdmin(uniqueKey());
        addMemberAs(adminToken, key2, ownerId, "OWNER");
        addMemberAs(adminToken, key2, viewerId, "OWNER");
        changeRoleAs(adminToken, key2, ownerId, "VIEWER");
        ProcessMemberEntity demoted = processMemberRepository.findById(
                new ProcessMemberId(processRepository.findByDefinitionKey(key2).orElseThrow().getId(), ownerId))
                .orElseThrow();
        assertEquals("VIEWER", demoted.getRole());
    }
}
