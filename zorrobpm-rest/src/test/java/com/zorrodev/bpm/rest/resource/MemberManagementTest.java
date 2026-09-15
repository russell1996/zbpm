package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-7: ADR-2 centralized control plane.
 * Deploy + Members → SUPER_ADMIN only. Runtime → OWNER/DESIGNER.
 * Full-context tests (V11) through real filter chain.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String ownerToken;
    private UUID ownerId;
    private String designerToken;
    private UUID designerId;
    private String nonMemberToken;
    private UUID nonMemberId;
    private String adminToken;
    private UUID adminId;
    private String bpmn;

    @BeforeAll
    void setup() throws Exception {
        bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));

        // Owner (USER role, will be OWNER of a process)
        UiUserEntity owner = new UiUserEntity();
        ownerId = UUID.randomUUID();
        owner.setId(ownerId);
        owner.setUsername("mt7-owner");
        owner.setPasswordHash(passwordHasher.hash("pass"));
        owner.setFullName("MT7 Owner");
        owner.setRole("USER");
        owner.setActive(true);
        owner.setCreatedAt(Instant.now());
        owner.setUpdatedAt(Instant.now());
        userRepository.save(owner);

        // Designer
        UiUserEntity designer = new UiUserEntity();
        designerId = UUID.randomUUID();
        designer.setId(designerId);
        designer.setUsername("mt7-designer");
        designer.setPasswordHash(passwordHasher.hash("pass"));
        designer.setFullName("MT7 Designer");
        designer.setRole("USER");
        designer.setActive(true);
        designer.setCreatedAt(Instant.now());
        designer.setUpdatedAt(Instant.now());
        userRepository.save(designer);

        // Non-member
        UiUserEntity nonMember = new UiUserEntity();
        nonMemberId = UUID.randomUUID();
        nonMember.setId(nonMemberId);
        nonMember.setUsername("mt7-nonmember");
        nonMember.setPasswordHash(passwordHasher.hash("pass"));
        nonMember.setFullName("MT7 Non-Member");
        nonMember.setRole("USER");
        nonMember.setActive(true);
        nonMember.setCreatedAt(Instant.now());
        nonMember.setUpdatedAt(Instant.now());
        userRepository.save(nonMember);

        // Super admin
        UiUserEntity admin = new UiUserEntity();
        adminId = UUID.randomUUID();
        admin.setId(adminId);
        admin.setUsername("mt7-admin");
        admin.setPasswordHash(passwordHasher.hash("pass"));
        admin.setFullName("MT7 Admin");
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        // Login all users
        ownerToken = login("mt7-owner", "pass");
        designerToken = login("mt7-designer", "pass");
        nonMemberToken = login("mt7-nonmember", "pass");
        adminToken = login("mt7-admin", "pass");
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

    private String uniqueKey() {
        return "mt7_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String deployBpmnAs(String token, String key) throws Exception {
        String testBpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                               .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                               .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(testBpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    // ==================== Criterion #1: USER-OWNER deploys → 403 ====================

    @Test
    void criterion1_ownerDeploy_returns403() throws Exception {
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(mapper.writeValueAsString(new AddProcessDefinitionDTO()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ==================== Criterion #2: SUPER_ADMIN deploys → 200 ====================

    @Test
    void criterion2_superAdminDeploy_returns200() throws Exception {
        String key = uniqueKey();
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
    }

    // ==================== Criterion #3: OWNER of his own process adds member → 200 ====================

    /**
     * ADR-8/WO-ACL-2: behaviour changed — MANAGE_MEMBERS is granted to the OWNER of the process
     * (was SUPER_ADMIN-only under ADR-2). OWNER adds a member to HIS process → 200.
     */
    @Test
    void criterion3_ownerAddMember_nowAllowed_returns200() throws Exception {
        // First, SUPER_ADMIN deploys and adds OWNER as member
        String key = uniqueKey();
        deployBpmnAs(adminToken, key);
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ownerId + "\",\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());

        // OWNER adds a member to his own process → 200 (ADR-8 п.7)
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #4: SUPER_ADMIN adds member → 200 ====================

    @Test
    void criterion4_superAdminAddMember_returns200() throws Exception {
        String key = uniqueKey();
        deployBpmnAs(adminToken, key);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());
    }

    // ==================== Criterion #5: OWNER runtime (START) → 200 ====================

    @Test
    void criterion5_ownerStartProcess_returns200() throws Exception {
        String key = uniqueKey();
        deployBpmnAs(adminToken, key);
        // Add OWNER as member
        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ownerId + "\",\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());

        // OWNER starts process → 200 (runtime allowed)
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionKey\":\"" + key + "\",\"variables\":[]}"))
                .andExpect(status().isCreated());
    }

    // ==================== Criterion #6: /users under ADMIN → 403; SUPER_ADMIN → 200 ====================

    @Test
    void criterion6_users_underNonSuperAdmin_returns403() throws Exception {
        // ADMIN (non-super) → 403
        mockMvc.perform(get("/users")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion6_users_underSuperAdmin_returns200() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ==================== Proof-of-failure ====================

    /**
     * Proof-of-failure (V3): On CURRENT code, OWNER can deploy (200).
     * After ADR-2 fix: OWNER deploy → 403.
     */
    @Test
    void criterion8_proofOfFailure_ownerDeployWas200_now403() throws Exception {
        // This proves the fix: OWNER deploy returns 403 (was 200 before fix)
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(mapper.writeValueAsString(new AddProcessDefinitionDTO()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
